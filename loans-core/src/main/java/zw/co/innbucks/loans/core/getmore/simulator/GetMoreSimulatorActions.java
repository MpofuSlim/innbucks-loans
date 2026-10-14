package zw.co.innbucks.loans.core.getmore.simulator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreCalls;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanRepository;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncTrigger;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherSync;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherCodeVault;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherResponse;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * STAGING ONLY: the test actions behind {@code /lending/v1/getmore-simulator/**}, each the simulated store doing what a
 * GetMore till would (a sale, an invoice, a refund, a code lapsing) in a transaction of its own, then loans reading
 * it back exactly as it reads GetMore: a {@link GetMoreVoucherSync} of the voucher, at once and on this thread, so the
 * next read of the loan already shows it (D21, §8.7). The store's answer is given in Magento's shapes with every code
 * masked; the voucher is the loans voucher after the sync.
 *
 * <p>Nothing here holds a transaction open: the store's work commits on its own, then the sync calls the store with no
 * transaction open, as it must. Every use, refused or not, is logged at WARN with the code masked, audited
 * ({@code GETMORE_SIMULATOR_<ACTION>}, actor and channel {@value #CHANNEL}, the caller's address, forwarded-for and key
 * fingerprint, never the key) and counted on {@value #METRIC}{@code {action}}. Exists only in SIMULATED.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "loans.getmore", name = "mode", havingValue = "SIMULATED")
public class GetMoreSimulatorActions {

    static final String METRIC = "loans.getmore.simulator.actions";
    static final String CHANNEL = "getmore-simulator";

    /** The actions, as the audit and the metric name them. */
    public enum Action { SALE, INVOICE, REFUND, COUPON_EXPIRY, COUPON_LOOKUP, FAULTS, FAULTS_CLEARED }

    private final SimulatedTill till;
    private final SimulatorFaults faults;
    private final GetMoreVoucherSync sync;
    private final VoucherService voucherService;
    private final VoucherRepository voucherRepository;
    private final StaffLoanRepository loanRepository;
    private final VoucherCodeVault vault;
    private final AuditService auditService;
    private final GetMoreProperties properties;
    private final MarketTimeZone marketTimeZone;
    private final TransactionTemplate readTransaction;
    private final Map<Action, Counter> counters = new EnumMap<>(Action.class);

    public GetMoreSimulatorActions(SimulatedTill till, SimulatorFaults faults, GetMoreVoucherSync sync,
                                   VoucherService voucherService, VoucherRepository voucherRepository,
                                   StaffLoanRepository loanRepository, VoucherCodeVault vault,
                                   AuditService auditService, GetMoreProperties properties,
                                   MarketTimeZone marketTimeZone, PlatformTransactionManager transactionManager,
                                   ObjectProvider<MeterRegistry> meterRegistry) {
        this.till = till;
        this.faults = faults;
        this.sync = sync;
        this.voucherService = voucherService;
        this.voucherRepository = voucherRepository;
        this.loanRepository = loanRepository;
        this.vault = vault;
        this.auditService = auditService;
        this.properties = properties;
        this.marketTimeZone = marketTimeZone;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTransaction.setReadOnly(true);
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            for (Action action : Action.values()) {
                counters.put(action, Counter.builder(METRIC).tag("action", action.name())
                        .description("Staging test actions on the simulated GetMore store").register(registry));
            }
        }
    }

    /**
     * Spends part of a voucher at an outlet, then syncs it. A replay (the same reference, voucher and amount) answers
     * the first sale and spends nothing more.
     *
     * @throws NotFoundException          the loan has no GetMore voucher
     * @throws SimulatorRefusedException  the till refused (not valid, expired, too much, the wrong currency, a
     *                                    reference used for another sale, or not this host's simulated voucher)
     */
    public SimAction<SimActionResponse> sell(SimSaleRequest request, SimCaller caller) {
        return run(Action.SALE, caller, trail -> {
            Target target = target(request.loanReference(), request.code());
            trail.target(target);
            requireSimulated(target);
            SimSale sale = till.sell(target.code(), request.amount(), request.currency(), request.basketTotal(),
                    request.outletId(), request.outletName(), request.reference(), request.invoicedNow());
            trail.put("order", sale.orderNumber()).put("amount", sale.amount()).put("outlet", request.outletId())
                    .put("invoiced", request.invoicedNow()).put("replayed", sale.replayed());
            Synced synced = syncCoupon(sale.couponId(), trail);
            String message;
            if (sale.replayed()) {
                message = "Sale " + sale.orderNumber() + " was already made under reference " + request.reference()
                        + "; nothing more was spent";
            } else if (request.invoicedNow()) {
                message = "Sale " + sale.orderNumber() + ": " + money(sale.currency(), sale.amount())
                        + " off the voucher; " + money(sale.currency(), sale.balance()) + " left";
            } else {
                message = "Sale " + sale.orderNumber() + ": " + money(sale.currency(), sale.amount())
                        + " held on the voucher until it is invoiced; " + money(sale.currency(), sale.balance())
                        + " left";
            }
            return new SimAction<>(message, sale.replayed(), new SimActionResponse(saleStore(sale), synced.voucher(),
                    synced.summary(), null, assumptions()));
        });
    }

    /**
     * Invoices a held sale in full, then syncs its voucher: what it took becomes spent.
     *
     * @throws SimulatorRefusedException no such sale, or nothing left to invoice on it
     */
    public SimAction<SimActionResponse> invoice(String orderNumber, SimCaller caller) {
        return run(Action.INVOICE, caller, trail -> {
            trail.put("order", orderNumber);
            SimSale sale = till.invoice(orderNumber);
            trail.put("amount", sale.amount());
            Synced synced = syncCoupon(sale.couponId(), trail);
            return new SimAction<>("Sale " + sale.orderNumber() + " invoiced: " + money(sale.currency(),
                    sale.amount()) + " off the voucher", false, new SimActionResponse(saleStore(sale),
                    synced.voucher(), synced.summary(), null, assumptions()));
        });
    }

    /**
     * Refunds a sale (all that is still refundable when no amount is given) with one credit memo, then syncs its
     * voucher: the amount is back on it (assumption A3). A replay answers the first refund.
     *
     * @throws SimulatorRefusedException no such sale, nothing invoiced left to refund, too much, or a reference used
     *                                   for another refund
     */
    public SimAction<SimActionResponse> refund(SimRefundRequest request, SimCaller caller) {
        return run(Action.REFUND, caller, trail -> {
            trail.put("order", request.orderNumber());
            SimRefund refund = till.refund(request.orderNumber(), request.amount(), request.reference());
            trail.put("creditMemo", refund.creditMemoNumber()).put("amount", refund.amount())
                    .put("replayed", refund.replayed());
            Synced synced = syncCoupon(refund.couponId(), trail);
            String message = refund.replayed()
                    ? "Credit memo " + refund.creditMemoNumber() + " was already made under reference "
                    + request.reference() + "; nothing more was refunded"
                    : "Credit memo " + refund.creditMemoNumber() + ": " + money(refund.currency(), refund.amount())
                    + " back on the voucher; " + money(refund.currency(), refund.balance()) + " left";
            Map<String, Object> store = new LinkedHashMap<>();
            store.put("creditMemo", refund.creditMemo());
            store.put("balance", refund.balance());
            store.put("replayed", refund.replayed());
            return new SimAction<>(message, refund.replayed(), new SimActionResponse(store, synced.voucher(),
                    synced.summary(), null, assumptions()));
        });
    }

    /**
     * Expires a simulated voucher now (§8.6): its coupon's {@code expiration_date} set through the store's PUT, the
     * voucher stopped ({@code SIMULATOR_EXPIRED}), then a sync, which withdraws the lapsed code at the store.
     *
     * @throws NotFoundException         no GetMore voucher for the loan or code
     * @throws SimulatorRefusedException {@code NOT_A_SIMULATED_VOUCHER}
     * @throws ConflictException         it is closed already (spent, cancelled or lapsed)
     */
    public SimAction<SimActionResponse> expire(SimCodeRequest request, SimCaller caller) {
        return run(Action.COUPON_EXPIRY, caller, trail -> {
            Target target = target(request.loanReference(), request.code());
            trail.target(target).put("reason", request.reason());
            if (target.voucherId() == null) {
                throw new NotFoundException("No GetMore voucher has that code");
            }
            requireSimulated(target);
            if (!target.status().isOpen()) {
                throw new ConflictException("Voucher " + target.voucherId() + " is " + target.status() + " already");
            }
            GetMoreCoupon coupon = till.expire(target.code(), marketTimeZone.nowUtc());
            voucherService.expireNowForSimulation(target.voucherId());
            GetMoreSyncResult result = sync.sync(target.voucherId(), GetMoreSyncTrigger.SIMULATOR);
            VoucherResponse voucher = voucherAfter(result, target.voucherId());
            trail.put("sync", result.outcome()).put("couponWithdrawn", result.couponWithdrawn());
            Map<String, Object> store = new LinkedHashMap<>();
            store.put("coupon", SimShapes.coupon(coupon));
            return new SimAction<>("Voucher " + target.voucherId() + " expired now (simulation) with "
                    + money(voucher.currency(), voucher.balance()) + " unspent", false, new SimActionResponse(store,
                    voucher, SimSyncSummary.of(result), result.couponWithdrawn(), assumptions()));
        });
    }

    /**
     * The simulated store's own view of a coupon, named by its loan or its code (in the body, never a URL), withdrawn
     * or not.
     *
     * @throws NotFoundException         the loan has no GetMore voucher
     * @throws SimulatorRefusedException no coupon has the code, or the voucher is not this host's simulated one
     */
    public SimAction<SimLookupResponse> lookup(SimCodeRequest request, SimCaller caller) {
        return run(Action.COUPON_LOOKUP, caller, trail -> {
            Target target = target(request.loanReference(), request.code());
            trail.target(target);
            requireSimulated(target);
            SimLookup lookup = till.lookup(target.code());
            Map<String, Object> store = new LinkedHashMap<>();
            if (lookup.coupon() != null) {
                store.put("coupon", lookup.coupon());   // absent once withdrawn, as Magento omits what it lacks
            }
            if (lookup.rule() != null) {
                store.put("rule", lookup.rule());
            }
            store.put("orders", lookup.orders());
            store.put("creditmemos", lookup.creditmemos());
            return new SimAction<>("The store's view of coupon " + GetMoreCodes.maskedCode(target.code()), false,
                    new SimLookupResponse(store, lookup.allowance(), lookup.allowanceCurrency(), lookup.balance(),
                            lookup.withdrawn(), lookup.assumptions()));
        });
    }

    /** Queues a fault for the store's next calls of one operation. */
    public SimAction<SimFaultsResponse> injectFault(SimFaultRequest request, SimCaller caller) {
        return run(Action.FAULTS, caller, trail -> {
            int times = request.timesOrOne();
            trail.put("operation", request.operation()).put("kind", request.kind()).put("times", times);
            faults.inject(request.operation(), request.kind(), times);
            return new SimAction<>("The next " + times + " " + request.operation() + " call(s) will "
                    + request.kind(), false, new SimFaultsResponse(faults.pending(), assumptions()));
        });
    }

    /** Drops every queued fault. */
    public SimAction<SimFaultsResponse> clearFaults(SimCaller caller) {
        return run(Action.FAULTS_CLEARED, caller, trail -> {
            faults.clear();
            return new SimAction<>("Faults cleared", false, new SimFaultsResponse(faults.pending(), assumptions()));
        });
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Runs one action: never inside a transaction; logged, audited and counted whatever its outcome. */
    private <T> T run(Action action, SimCaller caller, Function<Trail, T> work) {
        GetMoreCalls.requireNoTransaction();
        Trail trail = new Trail();
        String outcome = "OK";
        try {
            return work.apply(trail);
        } catch (SimulatorRefusedException refused) {
            outcome = refused.getRefusal().name();
            throw refused;
        } catch (NotFoundException notFound) {
            outcome = "NOT_FOUND";
            throw notFound;
        } catch (ConflictException conflict) {
            outcome = "CONFLICT";
            throw conflict;
        } catch (RuntimeException failed) {
            outcome = failed.getClass().getSimpleName();
            throw failed;
        } finally {
            log.warn("GetMore simulator: {}{} by key {} from {}: {}", action,
                    trail.maskedCode == null ? "" : " on " + trail.maskedCode, caller.keyFingerprint(),
                    caller.remoteAddress(), outcome);
            auditService.record(AuditLog.builder()
                    .eventType("GETMORE_SIMULATOR_" + action.name())
                    .entityType(trail.voucherId == null ? "GETMORE_SIMULATOR" : "VOUCHER")
                    .entityId(trail.voucherId == null ? null : String.valueOf(trail.voucherId))
                    .actorId(CHANNEL).channelUsed(CHANNEL)
                    .detail(caller.detail() + trail.detail() + ";outcome:" + outcome));
            Counter counter = counters.get(action);
            if (counter != null) {
                counter.increment();
            }
        }
    }

    /**
     * The voucher an action names, read in a transaction of its own: by its loan (whose GetMore voucher it must be,
     * the code then decrypted in memory) or by its code (the code as typed, the voucher if one was kept for it).
     */
    private Target target(String loanReference, String code) {
        if (!SimTargets.isBlank(loanReference)) {
            String reference = loanReference.strip();
            return readTransaction.execute(status -> loanRepository.findByReference(reference)
                    .flatMap(loan -> voucherRepository.findByStaffLoanId(loan.getId()))
                    .filter(Voucher::isGetMore)
                    .map(voucher -> target(voucher, vault.decrypt(voucher.getCodeCiphertext())))
                    .orElseThrow(() -> new NotFoundException("No GetMore voucher for loan " + reference)));
        }
        String typed = code.strip();
        Optional<Target> found = GetMoreCodes.normalize(typed).flatMap(normalised -> Optional.ofNullable(
                readTransaction.execute(status -> voucherRepository.findByCodeHmac(vault.fingerprint(normalised))
                        .filter(Voucher::isGetMore).map(voucher -> target(voucher, typed)).orElse(null))));
        return found.orElseGet(() -> new Target(null, null, typed, null, null));
    }

    private Target target(Voucher voucher, String code) {
        return new Target(voucher.getId(), voucher.getLoanAccount(), code, voucher.getStoreMode(),
                voucher.statusAt(marketTimeZone.nowUtc()));
    }

    /** A voucher of this host's simulated store, or none at all (a code no voucher was kept for). */
    private static void requireSimulated(Target target) {
        if (target.voucherId() != null && target.storeMode() != GetMoreMode.SIMULATED) {
            throw new SimulatorRefusedException(SimulatorRefusal.NOT_A_SIMULATED_VOUCHER, "Voucher "
                    + target.voucherId() + " is not a simulated GetMore voucher");
        }
    }

    /** Syncs the voucher kept for {@code couponId}, if one was; nothing when the coupon is no voucher's. */
    private Synced syncCoupon(long couponId, Trail trail) {
        Long voucherId = readTransaction.execute(status -> voucherRepository
                .findByStoreModeAndExternalCouponId(GetMoreMode.SIMULATED, couponId).map(Voucher::getId)
                .orElse(null));
        if (voucherId == null) {
            trail.put("coupon", couponId).put("sync", "NO_VOUCHER");
            return new Synced(null, null);
        }
        trail.voucherId = voucherId;
        GetMoreSyncResult result = sync.sync(voucherId, GetMoreSyncTrigger.SIMULATOR);
        trail.put("sync", result.outcome());
        return new Synced(voucherAfter(result, voucherId), SimSyncSummary.of(result));
    }

    private VoucherResponse voucherAfter(GetMoreSyncResult result, Long voucherId) {
        return result.voucher() != null ? result.voucher() : voucherService.voucher(voucherId).voucher();
    }

    private static Map<String, Object> saleStore(SimSale sale) {
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("order", sale.order());
        store.put("balance", sale.balance());
        store.put("replayed", sale.replayed());
        return store;
    }

    private List<String> assumptions() {
        return SimulatedGetMoreStore.assumptions(properties.getSimulator());
    }

    /** {@code USD 104.50}. */
    private static String money(String currency, BigDecimal amount) {
        return currency + " " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** A voucher named by an action. Holds its code in memory only, and never in toString. */
    private record Target(Long voucherId, String loanAccount, String code, GetMoreMode storeMode,
                          VoucherStatus status) {

        @Override
        public String toString() {
            return "Target[voucherId=" + voucherId + ", loanAccount=" + loanAccount + ", code="
                    + GetMoreCodes.maskedCode(code) + ", storeMode=" + storeMode + ", status=" + status + "]";
        }
    }

    private record Synced(VoucherResponse voucher, SimSyncSummary summary) {
    }

    /** What an action did, for its audit row and WARN line: the voucher, the masked code, and key:value pairs. */
    private static final class Trail {

        private Long voucherId;
        private String maskedCode;
        private final StringBuilder detail = new StringBuilder();

        Trail target(Target target) {
            voucherId = target.voucherId();
            maskedCode = GetMoreCodes.maskedCode(target.code());
            if (target.loanAccount() != null) {
                put("loan", target.loanAccount());
            }
            return put("code", maskedCode);
        }

        Trail put(String key, Object value) {
            if (value != null) {
                detail.append(';').append(key).append(':').append(value);
            }
            return this;
        }

        String detail() {
            return detail.toString();
        }
    }
}
