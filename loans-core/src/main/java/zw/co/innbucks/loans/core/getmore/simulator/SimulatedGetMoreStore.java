package zw.co.innbucks.loans.core.getmore.simulator;

import lombok.extern.slf4j.Slf4j;
import zw.co.innbucks.loans.core.getmore.GetMoreCalls;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponSpec;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponUpdate;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemo;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemoQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreException;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;
import zw.co.innbucks.loans.core.getmore.GetMoreOrderQuery;
import zw.co.innbucks.loans.core.getmore.GetMorePage;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;
import zw.co.innbucks.loans.core.getmore.GetMoreStore;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The SIMULATED {@link GetMoreStore} (staging only): GetMore's Magento store stood in for in process, over the
 * {@code getmore_sim_*} tables ({@link SimulatedStoreBackend}). Plain class, built only by the configuration's mode
 * switch.
 *
 * <p>Each call, like the live client's: no transaction open; then any fault a tester queued
 * ({@link SimulatorFaults#before}); then the backend, which commits in a transaction of its own, so what the store
 * did outlives a rollback of loans' work; then a write's lost answer, if one was queued. Never retried. One INFO line
 * per call, codes masked.
 *
 * <p><b>Not Magento</b>, and said so at every boot and in every test action's answer
 * ({@link #ASSUMPTIONS}): A1 each coupon is worth its own allowance; A2 it can be spent in parts; A3 a refund restores
 * its balance; A4 its {@code expiration_date} is enforced at the till. Unlike the live client, a prefix search is not
 * re-filtered here: it answers what Magento's SQL {@code LIKE} matches, {@code _} as a wildcard, and the callers above
 * the port re-filter (as they must for Magento too).
 */
@Slf4j
public final class SimulatedGetMoreStore implements GetMoreStore {

    /** The simulator's assumptions, in the words every test action answers with. */
    public static final List<String> ASSUMPTIONS = List.of("A1 per-coupon allowance", "A2 spending in parts",
            "A3 refund restores balance", "A4 coupon expiry enforced");

    private final SimulatedStoreBackend backend;
    private final SimulatorFaults faults;

    public SimulatedGetMoreStore(SimulatedStoreBackend backend, SimulatorFaults faults) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.faults = Objects.requireNonNull(faults, "faults");
    }

    @Override
    public GetMoreMode mode() {
        return GetMoreMode.SIMULATED;
    }

    @Override
    public List<String> generateCoupons(GetMoreCouponSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return call(Operation.GENERATE, () -> backend.generate(spec),
                codes -> codes.stream().map(GetMoreCodes::maskedCode).toList().toString());
    }

    @Override
    public GetMorePage<GetMoreCoupon> searchCoupons(GetMoreCouponQuery query) {
        Objects.requireNonNull(query, "query");
        return call(Operation.SEARCH_COUPONS, () -> backend.searchCoupons(query),
                page -> page.items().size() + " of " + page.totalCount());
    }

    @Override
    public GetMoreCoupon coupon(long couponId) {
        return call(Operation.GET_COUPON, () -> backend.coupon(couponId),
                c -> "coupon " + GetMoreCodes.maskedCode(c.code()));
    }

    @Override
    public GetMoreCoupon updateCoupon(long couponId, GetMoreCouponUpdate update) {
        Objects.requireNonNull(update, "update");
        return call(Operation.UPDATE_COUPON, () -> backend.updateCoupon(couponId, update),
                c -> "coupon " + GetMoreCodes.maskedCode(c.code()));
    }

    @Override
    public GetMoreCouponDeletion deleteCoupons(List<String> codesAsStored, boolean ignoreInvalidCoupons) {
        if (codesAsStored == null || codesAsStored.isEmpty()
                || codesAsStored.stream().anyMatch(c -> c == null || c.isBlank())) {
            throw new IllegalArgumentException("deleteByCodes needs at least one code, none blank");
        }
        return call(Operation.DELETE_COUPONS, () -> backend.deleteCoupons(codesAsStored, ignoreInvalidCoupons),
                d -> codesAsStored.size() + " sent, failed " + d.failedItems().size() + ", missing "
                        + d.missingItems().size());
    }

    @Override
    public GetMoreSalesRule salesRule(long ruleId) {
        return call(Operation.SALES_RULE, () -> backend.salesRule(ruleId), rule -> "rule " + rule.ruleId());
    }

    @Override
    public GetMorePage<GetMoreOrder> orders(GetMoreOrderQuery query) {
        Objects.requireNonNull(query, "query");
        return call(Operation.ORDERS, () -> backend.orders(query),
                page -> page.items().size() + " of " + page.totalCount());
    }

    @Override
    public GetMorePage<GetMoreCreditMemo> creditMemos(GetMoreCreditMemoQuery query) {
        Objects.requireNonNull(query, "query");
        return call(Operation.CREDIT_MEMOS, () -> backend.creditMemos(query),
                page -> page.items().size() + " of " + page.totalCount());
    }

    private <T> T call(Operation operation, Supplier<T> action, Function<T, String> describe) {
        GetMoreCalls.requireNoTransaction();
        long started = System.nanoTime();
        try {
            SimulatorFaults.FaultKind fault = faults.before(operation);
            T result = action.get();
            faults.after(operation, fault);
            log.info("GetMore {} (simulated) -> ok in {}ms ({})", operation, elapsedMs(started),
                    describe.apply(result));
            return result;
        } catch (GetMoreException failure) {
            log.info("GetMore {} (simulated) -> {} in {}ms", operation, failure.getClass().getSimpleName(),
                    elapsedMs(started));
            log.warn("{}", failure.getMessage());
            throw failure;
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
