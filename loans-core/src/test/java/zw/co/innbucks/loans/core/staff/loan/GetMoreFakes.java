package zw.co.innbucks.loans.core.staff.loan;

import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import zw.co.innbucks.loans.core.getmore.GetMoreCalls;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponSpec;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponUpdate;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemo;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemoQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreNotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;
import zw.co.innbucks.loans.core.getmore.GetMoreOrderQuery;
import zw.co.innbucks.loans.core.getmore.GetMorePage;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;
import zw.co.innbucks.loans.core.getmore.GetMoreStore;
import zw.co.innbucks.loans.core.voucher.VoucherCodeVault;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** In-memory stand-ins for the GetMore staff-loan tests: a clock, transactions that are really open, and a store. */
final class GetMoreFakes {

    static final long RULE = 43;
    /** The random part of Rutendo Chari's code, {@code SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C}: the first one minted. */
    static final String STORY_RANDOM = "7KQ2M9XWD4RT8B1C";

    private GetMoreFakes() {
    }

    /** The voucher vault with test keys: codes really sealed and fingerprinted. */
    static VoucherCodeVault vault() {
        VoucherProperties properties = new VoucherProperties();
        properties.setCodeHmacKey("u8Yc0m3l9G2pQ7vX1sR5tW4zN6bA0dE8fH3jK2LmPq0=");
        properties.setCodeEncryptionKey(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes()));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new VoucherCodeVault(properties, environment);
    }

    /** The vault with no keys: vouchers are off. */
    static VoucherCodeVault vaultOff() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new VoucherCodeVault(new VoucherProperties(), environment);
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * Transactions that are really open on the thread while their work runs (so a store call inside one fails, as a
     * real one does), each begin, commit and rollback recorded in {@code events}.
     */
    static final class Transactions extends AbstractPlatformTransactionManager {
        private final List<String> events;

        Transactions(List<String> events) {
            this.events = events;
            setTransactionSynchronization(SYNCHRONIZATION_ALWAYS);
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }

    /** GetMore's store, in memory: rule 43, coupons by prefix, generates as configured. Refuses an open transaction. */
    static final class Store implements GetMoreStore {
        final List<GetMoreCoupon> coupons = new CopyOnWriteArrayList<>();
        final List<String> deleted = new CopyOnWriteArrayList<>();
        final List<GetMoreCouponUpdate> updates = new CopyOnWriteArrayList<>();
        final List<String> events;
        final MutableClock clock;
        /** What a generate does; by default it mints the code and answers with it. */
        Function<GetMoreCouponSpec, List<String>> generateBehaviour = this::mint;
        RuntimeException searchFailure;
        RuntimeException deleteFailure;
        /** Runs at each call, after it is recorded: a test changes the world in the middle of an attempt. */
        Runnable onGenerate = () -> { };
        Runnable onSearch = () -> { };
        boolean failDeleting;
        int generateCalls;
        private long nextCouponId = 1207;
        private int minted;

        Store(MutableClock clock, List<String> events) {
            this.clock = clock;
            this.events = events;
        }

        private void enter(String event) {
            GetMoreCalls.requireNoTransaction();
            events.add(event);
        }

        /** Makes a code on the spec's prefix, at the store, and answers with it. */
        List<String> mint(GetMoreCouponSpec spec) {
            String random = ++minted == 1 ? STORY_RANDOM : STORY_RANDOM.substring(0, 15) + minted % 10;
            String code = spec.prefix() + random.substring(0, spec.length());
            plant(code, spec.ruleId(), 0, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            return List.of(code);
        }

        GetMoreCoupon plant(String code, long ruleId, int timesUsed, LocalDateTime createdAt) {
            GetMoreCoupon coupon = new GetMoreCoupon(nextCouponId++, ruleId, code, 0, 0, timesUsed, null, false,
                    createdAt, 1);
            coupons.add(coupon);
            return coupon;
        }

        void use(String code) {
            coupons.replaceAll(c -> c.code().equals(code) ? new GetMoreCoupon(c.couponId(), c.ruleId(), c.code(),
                    c.usageLimit(), c.usagePerCustomer(), c.timesUsed() + 1, c.expirationDateUtc(), c.primary(),
                    c.createdAtUtc(), c.type()) : c);
        }

        List<GetMoreCoupon> on(String prefix) {
            return coupons.stream().filter(c -> GetMoreCodes.hasPrefix(c.code(), prefix)).toList();
        }

        @Override
        public GetMoreMode mode() {
            return GetMoreMode.SIMULATED;
        }

        @Override
        public List<String> generateCoupons(GetMoreCouponSpec spec) {
            enter("generate");
            generateCalls++;
            onGenerate.run();
            return generateBehaviour.apply(spec);
        }

        @Override
        public GetMorePage<GetMoreCoupon> searchCoupons(GetMoreCouponQuery query) {
            enter("search");
            onSearch.run();
            if (searchFailure != null) {
                throw searchFailure;
            }
            List<GetMoreCoupon> matching = on(query.prefix());
            int from = Math.min(matching.size(), (query.page() - 1) * query.pageSize());
            int to = Math.min(matching.size(), from + query.pageSize());
            return new GetMorePage<>(matching.subList(from, to), matching.size(), query.page(), query.pageSize());
        }

        @Override
        public GetMoreCoupon coupon(long couponId) {
            enter("coupon");
            return coupons.stream().filter(c -> c.couponId() == couponId).findFirst()
                    .orElseThrow(() -> new GetMoreNotFoundException(Operation.GET_COUPON, "No such entity."));
        }

        @Override
        public GetMoreCoupon updateCoupon(long couponId, GetMoreCouponUpdate update) {
            enter("update");
            updates.add(update);
            GetMoreCoupon c = coupons.stream().filter(each -> each.couponId() == couponId).findFirst()
                    .orElseThrow(() -> new GetMoreNotFoundException(Operation.UPDATE_COUPON, "No such entity."));
            GetMoreCoupon updated = new GetMoreCoupon(c.couponId(), c.ruleId(), c.code(), c.usageLimit(),
                    c.usagePerCustomer(), c.timesUsed(), update.expirationDateUtc(), c.primary(), c.createdAtUtc(),
                    c.type());
            coupons.replaceAll(each -> each.couponId() == couponId ? updated : each);
            return updated;
        }

        @Override
        public GetMoreCouponDeletion deleteCoupons(List<String> codesAsStored, boolean ignoreInvalidCoupons) {
            enter("delete");
            if (deleteFailure != null) {
                throw deleteFailure;
            }
            if (failDeleting) {
                return new GetMoreCouponDeletion(codesAsStored, List.of());
            }
            List<String> missing = new ArrayList<>();
            for (String code : codesAsStored) {
                if (coupons.removeIf(c -> c.code().equals(code))) {
                    deleted.add(code);
                } else {
                    missing.add(code);
                }
            }
            return new GetMoreCouponDeletion(List.of(), missing);
        }

        @Override
        public GetMoreSalesRule salesRule(long ruleId) {
            enter("rule");
            return new GetMoreSalesRule(ruleId, "InnBucks Staff Grocery (SIMULATED)", true, null, null, "cart_fixed",
                    BigDecimal.ZERO, "AUTO", true, 0, 0, true, false);
        }

        @Override
        public GetMorePage<GetMoreOrder> orders(GetMoreOrderQuery query) {
            enter("orders");
            return new GetMorePage<>(List.of(), 0, query.page(), query.pageSize());
        }

        @Override
        public GetMorePage<GetMoreCreditMemo> creditMemos(GetMoreCreditMemoQuery query) {
            enter("memos");
            return new GetMorePage<>(List.of(), 0, query.page(), query.pageSize());
        }
    }
}
