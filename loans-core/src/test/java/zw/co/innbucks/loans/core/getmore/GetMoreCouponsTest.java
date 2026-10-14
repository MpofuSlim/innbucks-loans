package zw.co.innbucks.loans.core.getmore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException.Reason;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared find-or-create, activity and withdrawal logic, over an in-memory store that refuses to be called with a
 * transaction open (as every real one does) and a guard that records what it counted. What it pins is the rule that
 * makes a lost generate safe: every attempt searches the prefix first, a failed search never leads to a generate, the
 * generate is counted before it is sent and given back only when provably not processed, a code off the prefix is
 * withdrawn at once, and nothing loans did not make is ever adopted.
 */
class GetMoreCouponsTest {

    private static final long RULE = 43;
    private static final String PREFIX = "SIM-K7Q2M3XW-";
    private static final BigDecimal FACE = new BigDecimal("150.00");
    private static final LocalDate LAST_DAY = LocalDate.of(2026, 11, 13);
    private static final Instant START = Instant.parse("2026-10-14T08:20:05Z");
    /** The adoption floor: the issuance's first claim (two minutes ago) minus the clock skew (two minutes). */
    private static final LocalDateTime FLOOR = LocalDateTime.ofInstant(START, ZoneOffset.UTC).minusMinutes(4);

    private MutableClock clock;
    private FakeStore store;
    private RecordingBinding binding;
    private RecordingGuard guard;
    private GetMoreProperties properties;
    private GetMoreCoupons coupons;
    private List<String> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        clock = new MutableClock(START);
        store = new FakeStore(clock, events);
        store.rules.put(RULE, rule(RULE));
        binding = new RecordingBinding(events);
        guard = new RecordingGuard(events);
        properties = new GetMoreProperties();
        coupons = new GetMoreCoupons(store, binding, properties, new MarketTimeZone("ZW", clock));
    }

    private CouponRequest request() {
        return new CouponRequest(PREFIX, RULE, FACE, "USD", 16, LAST_DAY, FLOOR, guard);
    }

    private FindOrCreate findOrCreate() {
        return coupons.findOrCreate(request(), Deadline.none());
    }

    @Test
    @DisplayName("nothing on the prefix: the generate is counted BEFORE it is sent, sent once, then found and bound")
    void firstAttemptGeneratesOnce() {
        FindOrCreate result = findOrCreate();

        assertThat(result).isInstanceOfSatisfying(FindOrCreate.Bound.class, bound -> {
            assertThat(bound.adopted()).isFalse();
            assertThat(bound.coupon().code()).startsWith(PREFIX).hasSize(PREFIX.length() + 16);
            assertThat(bound.extrasWithdrawnMasked()).isEmpty();
        });
        assertThat(store.generateCalls).isEqualTo(1);
        assertThat(guard.counted).isEqualTo(1);
        assertThat(guard.givenBack).isZero();
        assertThat(events).containsSubsequence("salesRule", "precheck", "search", "guard.beforeGenerate", "generate",
                "search", "bind:" + FACE);
        assertThat(binding.bound).hasSize(1);
    }

    @Test
    @DisplayName("a generate whose answer was lost: outcome unknown, the count kept; the next attempt finds and adopts it")
    void lostResponseThenAdopt() {
        store.generateBehaviour = spec -> {
            store.mint(spec);
            throw new GetMoreOutcomeUnknownException(Operation.GENERATE, "the answer was lost");
        };

        assertThatThrownBy(this::findOrCreate).isInstanceOf(GetMoreOutcomeUnknownException.class);
        assertThat(guard.counted).isEqualTo(1);
        assertThat(guard.givenBack).as("it may have applied: the count stays").isZero();

        store.generateBehaviour = store::mint;
        FindOrCreate again = findOrCreate();

        assertThat(again).isInstanceOfSatisfying(FindOrCreate.Bound.class,
                bound -> assertThat(bound.adopted()).isTrue());
        assertThat(store.generateCalls).as("generated once, in total").isEqualTo(1);
        assertThat(guard.counted).isEqualTo(1);
        assertThat(store.coupons).hasSize(1);
    }

    @Test
    @DisplayName("a search that fails: it propagates, and nothing is counted or generated")
    void searchUnavailableNeverGenerates() {
        store.searchFailure = new GetMoreUnavailableException(Operation.SEARCH_COUPONS,
                GetMoreUnavailableException.Reason.READ_FAILED);

        assertThatThrownBy(this::findOrCreate).isInstanceOfSatisfying(GetMoreUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(GetMoreUnavailableException.Reason.READ_FAILED));
        assertThat(store.generateCalls).isZero();
        assertThat(guard.counted).isZero();
    }

    @Test
    @DisplayName("the generate allowance used up and nothing on the prefix: parked GENERATED_NOT_FOUND, nothing sent")
    void generateNotAllowedParksGeneratedNotFound() {
        guard.beforeFailure = new GenerateNotAllowedException("1 of 1 generates may have reached GetMore");

        assertThat(findOrCreate()).isInstanceOfSatisfying(FindOrCreate.Parked.class,
                parked -> assertThat(parked.outcome()).isEqualTo(FindOrCreate.GENERATED_NOT_FOUND));
        assertThat(store.generateCalls).isZero();
    }

    @Test
    @DisplayName("the claim lost before the generate: it propagates and nothing is sent")
    void claimLostSendsNothing() {
        guard.beforeFailure = new ClaimLostException("claim taken over");

        assertThatThrownBy(this::findOrCreate).isInstanceOf(ClaimLostException.class);
        assertThat(store.generateCalls).isZero();
    }

    @Test
    @DisplayName("a generate the store refused or never processed: the count is given back and the failure rethrown")
    void rejectedGenerateGivesCountBack() {
        store.generateBehaviour = spec -> {
            throw new GetMoreRejectedException(Operation.GENERATE, 400, Reason.INVALID, "rule not found");
        };
        assertThatThrownBy(this::findOrCreate).isInstanceOf(GetMoreRejectedException.class);
        assertThat(guard.counted).isEqualTo(1);
        assertThat(guard.givenBack).isEqualTo(1);

        store.generateBehaviour = spec -> {
            throw new GetMoreUnavailableException(Operation.GENERATE, GetMoreUnavailableException.Reason.NOT_REACHED);
        };
        assertThatThrownBy(this::findOrCreate).isInstanceOf(GetMoreUnavailableException.class);
        assertThat(guard.counted).isEqualTo(2);
        assertThat(guard.givenBack).isEqualTo(2);
        assertThat(store.coupons).isEmpty();
    }

    @Test
    @DisplayName("a code without the prefix: withdrawn AT ONCE with the codes as returned, then parked")
    void prefixNotHonouredWithdrawsAtOnce() {
        store.generateBehaviour = spec -> store.mint(new GetMoreCouponSpec(spec.ruleId(), 1, spec.length(),
                spec.format(), "OTHER-"));

        FindOrCreate result = findOrCreate();

        assertThat(result).isInstanceOfSatisfying(FindOrCreate.Parked.class, parked -> {
            assertThat(parked.outcome()).isEqualTo(FindOrCreate.PREFIX_NOT_HONOURED);
            assertThat(parked.liveOrphanCodes()).isEmpty();
            assertThat(parked.detail()).doesNotContain(store.lastMinted.getFirst());
        });
        assertThat(store.deleted).containsExactly(store.lastMinted.getFirst());
        assertThat(events).containsSubsequence("generate", "delete").doesNotContain("bind:" + FACE);
        assertThat(events.subList(events.indexOf("generate"), events.size())).doesNotContain("search");
        assertThat(store.coupons).isEmpty();
    }

    @Test
    @DisplayName("a code of the wrong length, or two codes for one: also withdrawn at once and parked")
    void wrongShapeWithdrawsAtOnce() {
        store.generateBehaviour = spec -> store.mint(new GetMoreCouponSpec(spec.ruleId(), 2, spec.length(),
                spec.format(), spec.prefix()));

        assertThat(findOrCreate()).isInstanceOfSatisfying(FindOrCreate.Parked.class,
                parked -> assertThat(parked.outcome()).isEqualTo(FindOrCreate.PREFIX_NOT_HONOURED));
        assertThat(store.deleted).hasSize(2);
        assertThat(store.coupons).isEmpty();
    }

    @Test
    @DisplayName("a code off the prefix whose withdrawal fails: parked with it as a live orphan")
    void prefixNotHonouredDeleteFailsKeepsOrphans() {
        store.generateBehaviour = spec -> store.mint(new GetMoreCouponSpec(spec.ruleId(), 1, spec.length(),
                spec.format(), "OTHER-"));
        store.deleteFailure = new GetMoreUnavailableException(Operation.DELETE_COUPONS,
                GetMoreUnavailableException.Reason.NOT_REACHED);

        FindOrCreate result = findOrCreate();

        assertThat(result).isInstanceOfSatisfying(FindOrCreate.Parked.class, parked -> {
            assertThat(parked.outcome()).isEqualTo(FindOrCreate.PREFIX_NOT_HONOURED);
            assertThat(parked.liveOrphanCodes()).containsExactlyElementsOf(store.lastMinted);
            assertThat(parked.toString()).doesNotContain(store.lastMinted.getFirst());
        });
    }

    @Test
    @DisplayName("a withdrawal that reports the code failed: kept as a live orphan too")
    void prefixNotHonouredDeleteReportsFailure() {
        store.generateBehaviour = spec -> store.mint(new GetMoreCouponSpec(spec.ruleId(), 1, spec.length(),
                spec.format(), "OTHER-"));
        store.failDeleting = true;

        FindOrCreate result = findOrCreate();

        assertThat(result).isInstanceOfSatisfying(FindOrCreate.Parked.class,
                parked -> assertThat(parked.liveOrphanCodes()).containsExactlyElementsOf(store.lastMinted));
    }

    @Test
    @DisplayName("a coupon on the prefix under another rule: foreign, parked, never generated over or bound")
    void foreignRuleParked() {
        store.plant(PREFIX + "AAAABBBBCCCCDDDD", 99, 0, FLOOR.plusMinutes(1));

        assertThat(findOrCreate()).isInstanceOfSatisfying(FindOrCreate.Parked.class,
                parked -> assertThat(parked.outcome()).isEqualTo(FindOrCreate.FOREIGN_COUPON));
        assertThat(store.generateCalls).isZero();
        assertThat(binding.bound).isEmpty();
    }

    @Test
    @DisplayName("a coupon on the prefix created before the first claim (less the skew): planted, never adopted")
    void prePlantedCouponParked() {
        store.plant(PREFIX + "AAAABBBBCCCCDDDD", RULE, 0, FLOOR.minusSeconds(1));

        assertThat(findOrCreate()).isInstanceOfSatisfying(FindOrCreate.Parked.class,
                parked -> assertThat(parked.outcome()).isEqualTo(FindOrCreate.FOREIGN_COUPON));
        assertThat(binding.bound).isEmpty();
    }

    @Test
    @DisplayName("our coupon found already used: someone had the code before loans bound it, so parked")
    void alreadyUsedParked() {
        store.plant(PREFIX + "AAAABBBBCCCCDDDD", RULE, 1, FLOOR.plusMinutes(1));

        assertThat(findOrCreate()).isInstanceOfSatisfying(FindOrCreate.Parked.class,
                parked -> assertThat(parked.outcome()).isEqualTo(FindOrCreate.COUPON_ALREADY_USED));
        assertThat(binding.bound).isEmpty();
    }

    @Test
    @DisplayName("two unused coupons on the prefix: the lowest id is kept and bound, the other withdrawn")
    void duplicatesKeepLowestUnusedAndWithdrawExtras() {
        GetMoreCoupon first = store.plant(PREFIX + "AAAABBBBCCCC1111", RULE, 0, FLOOR.plusMinutes(1));
        GetMoreCoupon second = store.plant(PREFIX + "AAAABBBBCCCC2222", RULE, 0, FLOOR.plusMinutes(2));

        FindOrCreate result = findOrCreate();

        assertThat(result).isInstanceOfSatisfying(FindOrCreate.Bound.class, bound -> {
            assertThat(bound.coupon().couponId()).isEqualTo(first.couponId());
            assertThat(bound.adopted()).isTrue();
            assertThat(bound.extrasWithdrawnMasked()).containsExactly("****2222");
        });
        assertThat(store.deleted).containsExactly(second.code());
        assertThat(store.generateCalls).isZero();
    }

    @Test
    @DisplayName("several on the prefix, exactly one used: that one is kept (and, adopted used, parked); the rest withdrawn")
    void exactlyOneUsedIsTheOneKept() {
        store.plant(PREFIX + "AAAABBBBCCCC1111", RULE, 0, FLOOR.plusMinutes(1));
        GetMoreCoupon used = store.plant(PREFIX + "AAAABBBBCCCC2222", RULE, 1, FLOOR.plusMinutes(2));

        FindOrCreate result = findOrCreate();

        assertThat(result).isInstanceOfSatisfying(FindOrCreate.Parked.class,
                parked -> assertThat(parked.outcome()).isEqualTo(FindOrCreate.COUPON_ALREADY_USED));
        assertThat(store.deleted).containsExactly(PREFIX + "AAAABBBBCCCC1111").doesNotContain(used.code());
    }

    @Test
    @DisplayName("several on the prefix, more than one used: parked DUPLICATE_COUPONS_USED, nothing withdrawn")
    void duplicatesUsedParked() {
        store.plant(PREFIX + "AAAABBBBCCCC1111", RULE, 1, FLOOR.plusMinutes(1));
        store.plant(PREFIX + "AAAABBBBCCCC2222", RULE, 2, FLOOR.plusMinutes(2));

        assertThat(findOrCreate()).isInstanceOfSatisfying(FindOrCreate.Parked.class,
                parked -> assertThat(parked.outcome()).isEqualTo(FindOrCreate.DUPLICATE_COUPONS_USED));
        assertThat(store.deleted).isEmpty();
        assertThat(binding.bound).isEmpty();
    }

    @Test
    @DisplayName("more coupons on the prefix than three pages: parked TOO_MANY_ON_PREFIX")
    void tooManyOnPrefixParked() {
        properties.setPageSize(1);
        for (int i = 0; i < 4; i++) {
            store.plant(PREFIX + "AAAABBBBCCCC000" + i, RULE, 0, FLOOR.plusMinutes(1));
        }

        assertThat(findOrCreate()).isInstanceOfSatisfying(FindOrCreate.Parked.class,
                parked -> assertThat(parked.outcome()).isEqualTo(FindOrCreate.TOO_MANY_ON_PREFIX));
        assertThat(store.searchCalls).isEqualTo(GetMoreCoupons.MAX_COUPON_PAGES);
    }

    @Test
    @DisplayName("the coupon already bound to another value: the binding's refusal propagates")
    void bindValueMismatch() {
        binding.bindFailure = new GetMoreRejectedException(null, Reason.VALUE_UNSUPPORTED,
                "already worth USD 100.00");

        assertThatThrownBy(this::findOrCreate).isInstanceOfSatisfying(GetMoreRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.VALUE_UNSUPPORTED));
    }

    @Test
    @DisplayName("the amount binding's precheck refuses: nothing is searched, counted or generated")
    void precheckRefusesBeforeAnything() {
        binding.precheckFailure = new GetMoreRejectedException(Operation.GENERATE, Reason.AMOUNT_MODEL_UNSET, "NONE");

        assertThatThrownBy(this::findOrCreate).isInstanceOf(GetMoreRejectedException.class);
        assertThat(store.searchCalls).isZero();
        assertThat(store.generateCalls).isZero();
        assertThat(guard.counted).isZero();
    }

    @Test
    @DisplayName("the deadline is checked BEFORE each call, never during one: a search running past it completes,"
            + " and the generate after it is never counted or sent")
    void deadlineCheckedBetweenCallsOnly() {
        store.callCost = Duration.ofSeconds(4);
        Deadline deadline = Deadline.after(clock, Duration.ofSeconds(6));

        assertThatThrownBy(() -> coupons.findOrCreate(request(), deadline))
                .isInstanceOfSatisfying(GetMoreUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(GetMoreUnavailableException.Reason.DEADLINE));

        assertThat(events).containsExactly("salesRule", "precheck", "search");
        assertThat(clock.instant()).as("the search started at +4s and ran to +8s").isEqualTo(START.plusSeconds(8));
        assertThat(guard.counted).isZero();
        assertThat(store.generateCalls).isZero();
    }

    @Test
    @DisplayName("called with a transaction open: refused before any call")
    void transactionOpenIllegalState() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(this::findOrCreate).isInstanceOf(IllegalStateException.class)
                    .hasMessage("GetMore is never called with a transaction open");
            assertThatThrownBy(() -> coupons.activity(PREFIX, Set.of(), Deadline.none()))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("activity: every order page until the total, memos for refunded and recorded orders in chunks of 100")
    void activityPagesUntilTotalAndChunksMemos() {
        properties.setPageSize(50);
        store.plant(PREFIX + "AAAABBBBCCCC1111", RULE, 101, FLOOR.plusMinutes(1));
        for (long id = 1; id <= 101; id++) {
            store.order(id, PREFIX + "AAAABBBBCCCC1111", new BigDecimal("-1.0000"), new BigDecimal("-0.5000"));
            store.memo(5000 + id, id, new BigDecimal("-0.5000"));
        }
        store.order(200, PREFIX + "AAAABBBBCCCC1111", new BigDecimal("-2.0000"), BigDecimal.ZERO);
        store.order(300, "OTHER-AAAABBBBCCCC1111", new BigDecimal("-2.0000"), new BigDecimal("-1.0000"));
        store.memo(9999, 999, new BigDecimal("-3.0000"));   // an order loans recorded a refund for, gone from the list

        CouponActivity activity = coupons.activity(PREFIX, Set.of(999L), Deadline.none());

        assertThat(activity.complete()).isTrue();
        assertThat(activity.couponsOnPrefix()).hasSize(1);
        assertThat(activity.ordersOnPrefix()).hasSize(102).extracting(GetMoreOrder::entityId).isSorted()
                .doesNotContain(300L);
        assertThat(store.orderPages).isEqualTo(3);
        assertThat(store.memoQueries).allSatisfy(q -> assertThat(q.orderIds().size()).isLessThanOrEqualTo(100));
        Set<Long> asked = new HashSet<>();
        store.memoQueries.forEach(q -> asked.addAll(q.orderIds()));
        assertThat(asked).hasSize(102).contains(999L).doesNotContain(200L, 300L);
        assertThat(activity.creditMemos()).hasSize(102).extracting(GetMoreCreditMemo::entityId).isSorted();
    }

    @Test
    @DisplayName("activity: more than 20 pages of orders is read no further and reported incomplete")
    void activityIncompleteOver20Pages() {
        properties.setPageSize(1);
        store.plant(PREFIX + "AAAABBBBCCCC1111", RULE, 21, FLOOR.plusMinutes(1));
        for (long id = 1; id <= 21; id++) {
            store.order(id, PREFIX + "AAAABBBBCCCC1111", new BigDecimal("-1.0000"), BigDecimal.ZERO);
        }

        CouponActivity activity = coupons.activity(PREFIX, Set.of(), Deadline.none());

        assertThat(activity.complete()).isFalse();
        assertThat(store.orderPages).isEqualTo(GetMoreCoupons.MAX_ORDER_PAGES);
        assertThat(activity.ordersOnPrefix()).hasSize(20);
    }

    @Test
    @DisplayName("withdraw sends deleteByCodes ignoring invalid codes, and nothing for no codes; setExpiry PUTs the expiry")
    void withdrawAndSetExpiry() {
        GetMoreCoupon planted = store.plant(PREFIX + "AAAABBBBCCCC1111", RULE, 0, FLOOR.plusMinutes(1));

        assertThat(coupons.withdraw(List.of()).missingItems()).isEmpty();
        assertThat(events).doesNotContain("delete");

        LocalDateTime expiry = LocalDateTime.of(2026, 11, 13, 21, 59, 59);
        assertThat(coupons.setExpiry(planted, expiry).expirationDateUtc()).isEqualTo(expiry);
        assertThat(store.lastUpdate.type()).isEqualTo(1);

        coupons.withdraw(List.of(planted.code()));
        assertThat(store.deleted).containsExactly(planted.code());
        assertThat(store.lastIgnoreInvalid).isTrue();
    }

    // ---- fakes -----------------------------------------------------------------------------------------------

    private static GetMoreSalesRule rule(long id) {
        return new GetMoreSalesRule(id, "InnBucks Staff Grocery (SIMULATED)", true, null, null, "cart_fixed",
                BigDecimal.ZERO, "AUTO", true, 0, 0, true, false);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
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
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** An in-memory Magento: refuses a call with a transaction open, records each call, pages by total. */
    static final class FakeStore implements GetMoreStore {
        final Map<Long, GetMoreSalesRule> rules = new HashMap<>();
        final List<GetMoreCoupon> coupons = new ArrayList<>();
        final List<GetMoreOrder> orders = new ArrayList<>();
        final List<GetMoreCreditMemo> memos = new ArrayList<>();
        final List<GetMoreCreditMemoQuery> memoQueries = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        final List<String> events;
        final MutableClock clock;
        Function<GetMoreCouponSpec, List<String>> generateBehaviour = this::mint;
        RuntimeException searchFailure;
        RuntimeException deleteFailure;
        boolean failDeleting;
        Duration callCost = Duration.ZERO;
        List<String> lastMinted = List.of();
        GetMoreCouponUpdate lastUpdate;
        boolean lastIgnoreInvalid;
        int generateCalls;
        int searchCalls;
        int orderPages;
        private long nextCouponId = 1201;
        private int minted;

        FakeStore(MutableClock clock, List<String> events) {
            this.clock = clock;
            this.events = events;
        }

        private void enter(String event) {
            GetMoreCalls.requireNoTransaction();
            events.add(event);
            clock.advance(callCost);
        }

        List<String> mint(GetMoreCouponSpec spec) {
            List<String> codes = new ArrayList<>();
            for (int i = 0; i < spec.quantity(); i++) {
                String random = String.format("%0" + spec.length() + "d", ++minted).replace('0', 'X');
                String code = spec.prefix() + random.substring(random.length() - spec.length());
                plant(code, spec.ruleId(), 0, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                codes.add(code);
            }
            lastMinted = List.copyOf(codes);
            return codes;
        }

        GetMoreCoupon plant(String code, long ruleId, int timesUsed, LocalDateTime createdAt) {
            GetMoreCoupon coupon = new GetMoreCoupon(nextCouponId++, ruleId, code, 0, 0, timesUsed, null, false,
                    createdAt, 1);
            coupons.add(coupon);
            return coupon;
        }

        void order(long id, String code, BigDecimal amount, BigDecimal refunded) {
            orders.add(new GetMoreOrder(id, "SIM" + id, code, Set.of(RULE), "complete", "USD", amount.negate(),
                    BigDecimal.ZERO, amount, amount, refunded, BigDecimal.ZERO, 2, "GetMore", FLOOR, FLOOR));
        }

        void memo(long id, long orderId, BigDecimal amount) {
            memos.add(new GetMoreCreditMemo(id, "SIMCM" + id, orderId, 2, amount, amount.negate(), BigDecimal.ZERO,
                    FLOOR));
        }

        @Override
        public GetMoreMode mode() {
            return GetMoreMode.SIMULATED;
        }

        @Override
        public List<String> generateCoupons(GetMoreCouponSpec spec) {
            enter("generate");
            generateCalls++;
            return generateBehaviour.apply(spec);
        }

        @Override
        public GetMorePage<GetMoreCoupon> searchCoupons(GetMoreCouponQuery query) {
            enter("search");
            searchCalls++;
            if (searchFailure != null) {
                throw searchFailure;
            }
            List<GetMoreCoupon> matching = coupons.stream()
                    .filter(c -> GetMoreCodes.hasPrefix(c.code(), query.prefix())).toList();
            return page(matching, query.page(), query.pageSize());
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
            lastUpdate = update;
            GetMoreCoupon c = coupon(couponId);
            GetMoreCoupon updated = new GetMoreCoupon(c.couponId(), c.ruleId(), c.code(), c.usageLimit(),
                    c.usagePerCustomer(), c.timesUsed(), update.expirationDateUtc(), c.primary(), c.createdAtUtc(),
                    c.type());
            coupons.replaceAll(each -> each.couponId() == couponId ? updated : each);
            return updated;
        }

        @Override
        public GetMoreCouponDeletion deleteCoupons(List<String> codesAsStored, boolean ignoreInvalidCoupons) {
            enter("delete");
            lastIgnoreInvalid = ignoreInvalidCoupons;
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
            enter("salesRule");
            GetMoreSalesRule rule = rules.get(ruleId);
            if (rule == null) {
                throw new GetMoreNotFoundException(Operation.SALES_RULE, "No such entity.");
            }
            return rule;
        }

        @Override
        public GetMorePage<GetMoreOrder> orders(GetMoreOrderQuery query) {
            enter("orders");
            orderPages++;
            List<GetMoreOrder> matching = orders.stream()
                    .filter(o -> GetMoreCodes.hasPrefix(o.couponCode(), query.couponPrefix())).toList();
            return page(matching, query.page(), query.pageSize());
        }

        @Override
        public GetMorePage<GetMoreCreditMemo> creditMemos(GetMoreCreditMemoQuery query) {
            enter("creditMemos");
            memoQueries.add(query);
            List<GetMoreCreditMemo> matching = memos.stream()
                    .filter(m -> query.orderIds().contains(m.orderId())).toList();
            return page(matching, query.page(), query.pageSize());
        }

        private static <T> GetMorePage<T> page(List<T> all, int page, int pageSize) {
            int from = Math.min(all.size(), (page - 1) * pageSize);
            int to = Math.min(all.size(), from + pageSize);
            return new GetMorePage<>(all.subList(from, to), all.size(), page, pageSize);
        }
    }

    static final class RecordingBinding implements GetMoreAmountBinding {
        final List<GetMoreCoupon> bound = new ArrayList<>();
        final List<String> events;
        RuntimeException precheckFailure;
        RuntimeException bindFailure;

        RecordingBinding(List<String> events) {
            this.events = events;
        }

        @Override
        public void precheck(GetMoreSalesRule rule, BigDecimal faceValue, String currency, LocalDate voucherLastDay) {
            if (precheckFailure != null) {
                throw precheckFailure;
            }
            events.add("precheck");
        }

        @Override
        public void bind(GetMoreCoupon coupon, BigDecimal faceValue, String currency) {
            if (bindFailure != null) {
                throw bindFailure;
            }
            events.add("bind:" + faceValue);
            bound.add(coupon);
        }

        @Override
        public boolean partialSpending() {
            return true;
        }

        @Override
        public String name() {
            return "RECORDING";
        }
    }

    static final class RecordingGuard implements GenerateGuard {
        final List<String> events;
        RuntimeException beforeFailure;
        int counted;
        int givenBack;

        RecordingGuard(List<String> events) {
            this.events = events;
        }

        @Override
        public void beforeGenerate() {
            if (beforeFailure != null) {
                throw beforeFailure;
            }
            events.add("guard.beforeGenerate");
            counted++;
        }

        @Override
        public void notProcessed() {
            events.add("guard.notProcessed");
            givenBack++;
        }
    }
}
