package zw.co.innbucks.loans.core.getmore.simulator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.GetMoreCalls;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponUpdate;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreStore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The simulated store's own checkout, for the staging test actions: what a GetMore till would do when a borrower spends
 * a voucher, an order is invoiced, a sale is refunded or a code lapses. Nothing here is a Magento REST call: it makes
 * the orders and credit memos that the store's REST surface ({@link SimulatedStoreBackend}) then answers with, so loans
 * reads them back exactly as it would read GetMore's.
 *
 * <p>{@link #sell}, {@link #invoice}, {@link #refund} and {@link #lookup} each commit in a transaction of their own.
 * {@link #expire} is deliberately NOT transactional: it reads the coupon in a transaction of its own, then goes through
 * the PORT's PUT with no transaction open, so the store's write path is exercised as in production. Every refusal is a
 * {@link SimulatorRefusedException}; a full code never reaches a log or a message.
 *
 * <p>Assumptions, not Magento ({@link SimulatedGetMoreStore#assumptions}): a coupon's balance is its allowance less
 * what its orders took plus what refunds gave back ({@link SimulatedBalance}); a held (not yet invoiced) sale counts
 * against it, as at a real till; with spending in parts on, any amount from 0.01 to the balance (in cents), any number
 * of times, and with it off exactly the balance, once (a used coupon is refused, as a single-use one is, even after a
 * refund); the coupon's {@code expiration_date} is enforced when enforcement is on. A coupon is spent only in the
 * currency its allowance was bound in.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "loans.getmore", name = "mode", havingValue = "SIMULATED")
public class SimulatedTill {

    static final int MAX_REFERENCE = 64;
    static final int MAX_OUTLET_ID = 64;
    static final int MAX_OUTLET_NAME = 120;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.UNNECESSARY);
    private static final BigDecimal MIN_AMOUNT = new BigDecimal("0.01");
    /** The most the store's NUMERIC(12,4) amount columns hold in cents: refused above, never a database overflow. */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    private final SimCouponRepository coupons;
    private final SimRuleRepository rules;
    private final SimStoreRepository stores;
    private final SimOrderRepository orders;
    private final SimCreditMemoRepository creditMemos;
    private final GetMoreProperties properties;
    private final MarketTimeZone marketTimeZone;
    private final GetMoreStore store;
    private final TransactionTemplate readOnNewTransaction;

    public SimulatedTill(SimCouponRepository coupons, SimRuleRepository rules, SimStoreRepository stores,
                         SimOrderRepository orders, SimCreditMemoRepository creditMemos, GetMoreProperties properties,
                         MarketTimeZone marketTimeZone, GetMoreStore store,
                         PlatformTransactionManager transactionManager) {
        this.coupons = coupons;
        this.rules = rules;
        this.stores = stores;
        this.orders = orders;
        this.creditMemos = creditMemos;
        this.properties = properties;
        this.marketTimeZone = marketTimeZone;
        this.store = store;
        this.readOnNewTransaction = new TransactionTemplate(transactionManager);
        this.readOnNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnNewTransaction.setReadOnly(true);
    }

    /**
     * Spends {@code amount} of the coupon at an outlet: one order. With a {@code reference} it is idempotent: the same
     * coupon and amount again answers the first sale ({@code replayed}); anything else under it is refused.
     *
     * @param currency    null for the simulated store's currency
     * @param basketTotal what the basket came to, at least {@code amount}; null for exactly {@code amount}
     * @param outletName  null for {@code outletId}
     * @param reference   the till's own reference, up to 64 characters; null for {@code SIM-SALE-<uuid>}
     * @param invoiced    false leaves the sale held (ordered, not yet invoiced: not yet spent for loans)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SimSale sell(String code, BigDecimal amount, String currency, BigDecimal basketTotal, String outletId,
                        String outletName, String reference, boolean invoiced) {
        BigDecimal spend = money(amount, "amount");
        BigDecimal basket = basketTotal == null ? spend : money(basketTotal, "basketTotal");
        if (basket.compareTo(spend) < 0) {
            throw new IllegalArgumentException("basketTotal must be at least the amount taken off the coupon");
        }
        String outlet = required(outletId, "outletId", MAX_OUTLET_ID);
        String outletShownAs = isBlank(outletName) ? outlet : bounded(outletName, "outletName", MAX_OUTLET_NAME);
        String saleReference = isBlank(reference) ? "SIM-SALE-" + UUID.randomUUID()
                : bounded(reference, "reference", MAX_REFERENCE);
        String storeCurrency = simulator().getCurrency();
        String saleCurrency = isBlank(currency) ? storeCurrency : currency.strip().toUpperCase(Locale.ROOT);

        String codeAsTyped = nonBlank(code);
        SimCoupon coupon = coupons.lockLiveByCode(codeAsTyped)
                .orElseThrow(() -> notValid(code));
        if (!isBlank(reference)) {
            Optional<SimOrder> earlier = orders.findBySaleReference(saleReference);
            if (earlier.isPresent()) {
                return replayedSale(earlier.get(), coupon, spend);
            }
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        LocalDate today = marketTimeZone.today();
        SimRule rule = rules.findById(coupon.getRuleId()).orElseThrow(() -> notValid(code));
        boolean ruleOver = !rule.isActive() || (rule.getToDate() != null && rule.getToDate().isBefore(today));
        boolean couponOver = simulator().isEnforceCouponExpiry() && coupon.getExpirationDate() != null
                && !coupon.getExpirationDate().isAfter(now);
        if (ruleOver || couponOver) {
            throw new SimulatorRefusedException(SimulatorRefusal.SIM_COUPON_EXPIRED,
                    "The coupon code \"" + GetMoreCodes.maskedCode(coupon.getCode()) + "\" has expired.");
        }
        if (!saleCurrency.equals(storeCurrency)) {
            throw new SimulatorRefusedException(SimulatorRefusal.SIM_CURRENCY_MISMATCH,
                    "The simulated store sells in " + storeCurrency + ", not " + saleCurrency);
        }
        if (coupon.getAllowance() == null || usedUp(coupon)
                || (!simulator().isPartialSpending() && coupon.getTimesUsed() >= 1)) {
            // With spending in parts off the coupon is single use, as LIVE's RULE_AMOUNT_SINGLE_USE makes it: Magento
            // refuses a second use outright ("is not valid"), whatever a refund gave back, and loans' sync stops such
            // a voucher (SINGLE_USE_EXHAUSTED) once it is used for less than its value.
            throw notValid(coupon.getCode());
        }
        if (!saleCurrency.equals(coupon.getAllowanceCurrency())) {
            // The store's currency changed after the coupon was bound: its balance cannot be spent in another one.
            throw new SimulatorRefusedException(SimulatorRefusal.SIM_CURRENCY_MISMATCH, "This coupon is worth "
                    + coupon.getAllowanceCurrency() + ", not " + saleCurrency);
        }
        BigDecimal balance = balanceOf(coupon);
        if (spend.compareTo(balance) > 0) {
            throw new SimulatorRefusedException(SimulatorRefusal.SIM_INSUFFICIENT_BALANCE,
                    "The amount is more than the " + shown(coupon.getAllowanceCurrency(), balance)
                            + " left on this coupon");
        }
        if (!simulator().isPartialSpending() && spend.compareTo(balance) != 0) {
            throw new SimulatorRefusedException(SimulatorRefusal.SIM_PARTIAL_SPEND_NOT_ALLOWED,
                    "This coupon must be spent in one purchase of " + shown(coupon.getAllowanceCurrency(), balance));
        }

        stores.insertIfAbsent(outlet, outletShownAs, now);
        SimStore shop = stores.findByOutletCode(outlet).orElseThrow();
        String orderNumber = String.format("SIM%09d", orders.nextOrderNumber());
        // The code as the till received it, in the shopper's case: Magento records the code applied to the quote as
        // given and finds the coupon without case, so a live order's coupon_code can differ in case from the coupon.
        int inserted = orders.insertIfAbsent(orderNumber, saleReference, codeAsTyped,
                String.valueOf(rule.getRuleId()), shop.getStoreId(), shop.magentoStoreName(),
                invoiced ? SimOrder.COMPLETE : SimOrder.PROCESSING, saleCurrency, basket, basket.subtract(spend),
                spend.negate(), invoiced ? spend.negate() : ZERO, now);
        SimOrder order = orders.findBySaleReference(saleReference).orElseThrow();
        if (inserted == 0) {
            // A concurrent sale took the reference between our check and our insert.
            return replayedSale(order, coupon, spend);
        }
        coupon.setTimesUsed(coupon.getTimesUsed() + 1);
        rules.countUse(rule.getRuleId());
        BigDecimal left = balance.subtract(spend);
        log.info("GetMore simulator: sale {} of {} on coupon {} ({}) at {}{}; {} left", order.getIncrementId(),
                shown(saleCurrency, spend), coupon.getCouponId(), GetMoreCodes.maskedCode(coupon.getCode()), outlet,
                invoiced ? "" : " (held, not invoiced)", shown(saleCurrency, left));
        return new SimSale(order.getEntityId(), order.getIncrementId(), coupon.getCouponId(),
                GetMoreCodes.maskedCode(coupon.getCode()), SimShapes.order(order), spend, saleCurrency, left, false);
    }

    /** Invoices a held sale in full: what it took becomes spent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SimSale invoice(String orderNumber) {
        SimOrder order = orders.lockByIncrementId(nonBlank(orderNumber)).orElseThrow(() -> saleNotFound(orderNumber));
        BigDecimal toInvoice = order.spent().subtract(order.getDiscountInvoiced().abs())
                .subtract(order.getDiscountCanceled().abs());
        if (SimOrder.CANCELED.equals(order.getState()) || toInvoice.signum() <= 0) {
            throw new SimulatorRefusedException(SimulatorRefusal.SIM_NOTHING_TO_INVOICE,
                    "Sale " + order.getIncrementId() + " has nothing left to invoice");
        }
        order.setDiscountInvoiced(order.getDiscountAmount());
        order.setState(SimOrder.COMPLETE);
        order.setStatus(SimOrder.COMPLETE);
        order.setUpdatedAt(marketTimeZone.nowUtc());
        orders.flush();
        SimCoupon coupon = coupons.findAnyByCode(order.getCouponCode()).orElseThrow();
        log.info("GetMore simulator: sale {} invoiced, {} off coupon {} ({})", order.getIncrementId(),
                shown(order.getOrderCurrencyCode(), toInvoice), coupon.getCouponId(),
                GetMoreCodes.maskedCode(coupon.getCode()));
        return new SimSale(order.getEntityId(), order.getIncrementId(), coupon.getCouponId(),
                GetMoreCodes.maskedCode(coupon.getCode()), SimShapes.order(order), toInvoice,
                order.getOrderCurrencyCode(), balanceOf(coupon), false);
    }

    /**
     * Refunds invoiced value of a sale: one credit memo in state 2 (refunded), which gives it back to the coupon
     * (assumption A3). Idempotent on {@code reference}.
     *
     * @param amount    null for everything still refundable
     * @param reference null for {@code SIM-REFUND-<uuid>}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SimRefund refund(String orderNumber, BigDecimal amount, String reference) {
        BigDecimal asked = amount == null ? null : money(amount, "amount");
        String refundReference = isBlank(reference) ? "SIM-REFUND-" + UUID.randomUUID()
                : bounded(reference, "reference", MAX_REFERENCE);
        SimOrder order = orders.lockByIncrementId(nonBlank(orderNumber)).orElseThrow(() -> saleNotFound(orderNumber));
        if (!isBlank(reference)) {
            Optional<SimCreditMemo> earlier = creditMemos.findByRefundReference(refundReference);
            if (earlier.isPresent()) {
                return replayedRefund(earlier.get(), order, asked);
            }
        }
        BigDecimal refundable = order.refundable();
        if (refundable.signum() <= 0) {
            throw new SimulatorRefusedException(SimulatorRefusal.SIM_NOTHING_TO_REFUND,
                    "Sale " + order.getIncrementId() + " has nothing invoiced left to refund");
        }
        BigDecimal refund = asked == null ? refundable : asked;
        if (refund.compareTo(refundable) > 0) {
            throw new SimulatorRefusedException(SimulatorRefusal.SIM_REFUND_TOO_LARGE, "The amount is more than the "
                    + shown(order.getOrderCurrencyCode(), refundable) + " left to refund on sale "
                    + order.getIncrementId());
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        String memoNumber = String.format("SIMCM%08d", creditMemos.nextCreditMemoNumber());
        int inserted = creditMemos.insertIfAbsent(memoNumber, refundReference, order.getEntityId(),
                SimCreditMemo.REFUNDED, refund.negate(), refund, ZERO, now);
        SimCreditMemo memo = creditMemos.findByRefundReference(refundReference).orElseThrow();
        if (inserted == 0) {
            return replayedRefund(memo, order, asked);
        }
        order.setDiscountRefunded(order.getDiscountRefunded().subtract(refund));
        if (order.getDiscountRefunded().compareTo(order.getDiscountInvoiced()) == 0) {
            order.setState(SimOrder.CLOSED);
            order.setStatus(SimOrder.CLOSED);
        }
        order.setUpdatedAt(now);
        orders.flush();
        SimCoupon coupon = coupons.findAnyByCode(order.getCouponCode()).orElseThrow();
        BigDecimal balance = balanceOf(coupon);
        log.info("GetMore simulator: credit memo {} refunds {} of sale {} on coupon {} ({}); {} left",
                memo.getIncrementId(), shown(order.getOrderCurrencyCode(), refund), order.getIncrementId(),
                coupon.getCouponId(), GetMoreCodes.maskedCode(coupon.getCode()),
                shown(order.getOrderCurrencyCode(), balance));
        return new SimRefund(memo.getEntityId(), memo.getIncrementId(), order.getEntityId(), order.getIncrementId(),
                coupon.getCouponId(), GetMoreCodes.maskedCode(coupon.getCode()), SimShapes.creditMemo(memo), refund,
                order.getOrderCurrencyCode(), balance, false);
    }

    /**
     * Sets the coupon's {@code expiration_date} to {@code atUtc} through the PORT's PUT, with no transaction open, so
     * the store's write path is the one exercised. The coupon is read first in a transaction of its own.
     *
     * @throws IllegalStateException a transaction is open on this thread
     */
    public GetMoreCoupon expire(String code, LocalDateTime atUtc) {
        GetMoreCalls.requireNoTransaction();
        SimCoupon coupon = readOnNewTransaction.execute(status -> coupons.findLiveByCode(nonBlank(code)).orElse(null));
        if (coupon == null) {
            throw notValid(code);
        }
        GetMoreCoupon updated = store.updateCoupon(coupon.getCouponId(),
                new GetMoreCouponUpdate(coupon.getRuleId(), coupon.getCode(), coupon.getType(), atUtc));
        log.info("GetMore simulator: coupon {} ({}) expires at {} UTC", coupon.getCouponId(),
                GetMoreCodes.maskedCode(coupon.getCode()), atUtc);
        return updated;
    }

    /** The store's own view of a coupon, deleted or not, in Magento's shapes with every code masked. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public SimLookup lookup(String code) {
        SimCoupon coupon = coupons.findAnyByCode(nonBlank(code)).orElseThrow(() -> notValid(code));
        SimRule rule = rules.findById(coupon.getRuleId()).orElse(null);
        List<SimOrder> placed = orders.findByCouponCodeIgnoringCase(coupon.getCode());
        List<SimCreditMemo> memos = placed.isEmpty() ? List.of()
                : creditMemos.findByOrderIdInOrderByEntityIdAsc(placed.stream().map(SimOrder::getEntityId).toList());
        BigDecimal balance = coupon.getAllowance() == null ? null
                : SimulatedBalance.of(coupon.getAllowance(), placed, memos);
        return new SimLookup(coupon.isDeleted() ? null : SimShapes.coupon(coupon),
                rule == null ? null : SimShapes.rule(rule),
                SimShapes.searchResult(placed.stream().map(SimShapes::order).toList()),
                SimShapes.searchResult(memos.stream().map(SimShapes::creditMemo).toList()),
                coupon.getAllowance(), coupon.getAllowanceCurrency(), balance, coupon.isDeleted(),
                SimulatedGetMoreStore.assumptions(simulator()));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private SimSale replayedSale(SimOrder earlier, SimCoupon coupon, BigDecimal spend) {
        boolean same = coupon.getCode().equalsIgnoreCase(earlier.getCouponCode())
                && earlier.spent().compareTo(spend) == 0;
        if (!same) {
            throw new SimulatorRefusedException(SimulatorRefusal.SIM_REFERENCE_REUSED, "Sale reference "
                    + earlier.getSaleReference() + " was already used for a different sale ("
                    + earlier.getIncrementId() + ")");
        }
        log.info("GetMore simulator: sale {} replayed for its reference", earlier.getIncrementId());
        return new SimSale(earlier.getEntityId(), earlier.getIncrementId(), coupon.getCouponId(),
                GetMoreCodes.maskedCode(coupon.getCode()), SimShapes.order(earlier), spend,
                earlier.getOrderCurrencyCode(), balanceOf(coupon), true);
    }

    private SimRefund replayedRefund(SimCreditMemo earlier, SimOrder order, BigDecimal asked) {
        boolean same = earlier.getOrderId() == order.getEntityId()
                && (asked == null || earlier.getDiscountAmount().abs().compareTo(asked) == 0);
        if (!same) {
            throw new SimulatorRefusedException(SimulatorRefusal.SIM_REFERENCE_REUSED, "Refund reference "
                    + earlier.getRefundReference() + " was already used for a different refund ("
                    + earlier.getIncrementId() + ")");
        }
        SimCoupon coupon = coupons.findAnyByCode(order.getCouponCode()).orElseThrow();
        log.info("GetMore simulator: credit memo {} replayed for its reference", earlier.getIncrementId());
        return new SimRefund(earlier.getEntityId(), earlier.getIncrementId(), order.getEntityId(),
                order.getIncrementId(), coupon.getCouponId(), GetMoreCodes.maskedCode(coupon.getCode()),
                SimShapes.creditMemo(earlier), earlier.getDiscountAmount().abs(), order.getOrderCurrencyCode(),
                balanceOf(coupon), true);
    }

    private BigDecimal balanceOf(SimCoupon coupon) {
        if (coupon.getAllowance() == null) {
            return ZERO;
        }
        List<SimOrder> placed = orders.findByCouponCodeIgnoringCase(coupon.getCode());
        List<SimCreditMemo> memos = placed.isEmpty() ? List.of()
                : creditMemos.findByOrderIdInOrderByEntityIdAsc(placed.stream().map(SimOrder::getEntityId).toList());
        return SimulatedBalance.of(coupon.getAllowance(), placed, memos);
    }

    /** Magento refuses a coupon whose uses are used up; the simulated rule allows unlimited uses (0). */
    private static boolean usedUp(SimCoupon coupon) {
        Integer limit = coupon.getUsageLimit();
        return limit != null && limit > 0 && coupon.getTimesUsed() >= limit;
    }

    private GetMoreProperties.Simulator simulator() {
        return properties.getSimulator();
    }

    private static SimulatorRefusedException notValid(String code) {
        return new SimulatorRefusedException(SimulatorRefusal.SIM_COUPON_NOT_FOUND,
                "The coupon code \"" + GetMoreCodes.maskedCode(code == null ? "" : code.strip()) + "\" is not valid.");
    }

    private static SimulatorRefusedException saleNotFound(String orderNumber) {
        return new SimulatorRefusedException(SimulatorRefusal.SIM_SALE_NOT_FOUND,
                "No simulated sale has order number " + orderNumber);
    }

    /**
     * An amount of at least 0.01 with at most 2 decimals (assumption A2: a sale is any amount from 0.01, in the cents
     * loans' voucher ledger records) and at most {@link #MAX_AMOUNT}, held at the store's 4-decimal scale. Checked
     * before anything is written, so an oversized basket is a refusal, never a database overflow.
     */
    private static BigDecimal money(BigDecimal amount, String name) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be above 0");
        }
        if (amount.compareTo(MIN_AMOUNT) < 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(name + " must be at least 0.01 with at most 2 decimals");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException(name + " must be at most " + MAX_AMOUNT.toPlainString());
        }
        return amount.setScale(4, RoundingMode.UNNECESSARY);
    }

    /** {@code USD 104.50}. */
    private static String shown(String currency, BigDecimal amount) {
        return currency + " " + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nonBlank(String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("A code or order number is required");
        }
        return value.strip();
    }

    private static String required(String value, String name, int max) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        return bounded(value, name, max);
    }

    private static String bounded(String value, String name, int max) {
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new IllegalArgumentException(name + " must be at most " + max + " characters");
        }
        return stripped;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
