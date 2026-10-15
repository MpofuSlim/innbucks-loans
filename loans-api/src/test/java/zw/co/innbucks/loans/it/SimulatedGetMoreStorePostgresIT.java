package zw.co.innbucks.loans.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.getmore.CouponFormat;
import zw.co.innbucks.loans.core.getmore.CouponRequest;
import zw.co.innbucks.loans.core.getmore.Deadline;
import zw.co.innbucks.loans.core.getmore.FindOrCreate;
import zw.co.innbucks.loans.core.getmore.GenerateGuard;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponSpec;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponUpdate;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupons;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemo;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemoQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreNotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;
import zw.co.innbucks.loans.core.getmore.GetMoreOrderQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMorePage;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;
import zw.co.innbucks.loans.core.getmore.GetMoreStore;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.getmore.simulator.SimLookup;
import zw.co.innbucks.loans.core.getmore.simulator.SimRefund;
import zw.co.innbucks.loans.core.getmore.simulator.SimSale;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedGetMoreStore;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedStoreSeeder;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedTill;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorFaults;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorRefusal;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorRefusedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The simulated GetMore store's conformance, against real Postgres (V36, the seeder, the {@code REQUIRES_NEW}
 * transactions, the row locks and the {@code ON CONFLICT} inserts), asserted through the PORT's records exactly as
 * the code above it reads them, never through shared fixtures. Each part pins one Magento behaviour the simulator
 * claims (generate, search, PUT, deleteByCodes), one simulator assumption (the till: spending in parts, refunds,
 * expiry), or a concurrency rule (two tills, one coupon; two first sales at one outlet; one reference twice). The
 * {@code it} profile is not a deployment, so the node check is skipped; the key is 48 characters and shared with
 * nothing. The voucher code keys are set because SIMULATED refuses to start without them ({@code GetMoreDataGuard}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "it"})
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
@TestPropertySource(properties = {
        "loans.getmore.mode=SIMULATED",
        "loans.getmore.simulator.api-key=it-sim-3f9a1c7e5b2d4f6a8c0e1b3d5f7a9c2e4b6d8f0a1c3e",
        "loans.vouchers.code-hmac-key=it-voucher-hmac-key-0123456789abcdef0123456789",
        "loans.vouchers.code-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
class SimulatedGetMoreStorePostgresIT {

    private static final long RULE = 43;
    private static final String USD = "USD";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = ItPostgres.createDatabase("loans_getmore_sim_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ItPostgres::user);
        registry.add("spring.datasource.password", ItPostgres::password);
    }

    @Autowired private GetMoreStore store;
    @Autowired private GetMoreCoupons coupons;
    @Autowired private SimulatedTill till;
    @Autowired private SimulatorFaults faults;
    @Autowired private SimulatedStoreSeeder seeder;
    @Autowired private GetMoreProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void reset() {
        faults.clear();
        properties.getSimulator().setPartialSpending(true);
        properties.getSimulator().setEnforceCouponExpiry(true);
        properties.getSimulator().setCurrency(USD);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static String newPrefix() {
        return GetMoreCodes.prefixFor("SIM-", GetMoreCodes.newNonce());
    }

    /** A coupon found or made on a fresh prefix and bound to {@code face}, through the shared code above the port. */
    private GetMoreCoupon boundCoupon(String face) {
        CountingGuard guard = new CountingGuard();
        FindOrCreate result = coupons.findOrCreate(request(newPrefix(), face, guard), Deadline.none());
        assertThat(result).isInstanceOf(FindOrCreate.Bound.class);
        return ((FindOrCreate.Bound) result).coupon();
    }

    private static CouponRequest request(String prefix, String face, GenerateGuard guard) {
        return new CouponRequest(prefix, RULE, new BigDecimal(face), USD, 16, LocalDate.now(ZoneOffset.UTC).plusDays(30),
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(4), guard);
    }

    private SimSale sell(GetMoreCoupon coupon, String amount) {
        return till.sell(coupon.code(), new BigDecimal(amount), null, null, "GM-AVD", "GetMore Avondale", null, true);
    }

    private static void refusedAs(SimulatorRefusal refusal, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SimulatorRefusedException.class,
                e -> assertThat(e.getRefusal()).isEqualTo(refusal));
    }

    private List<GetMoreOrder> ordersOf(GetMoreCoupon coupon) {
        String prefix = coupon.code().substring(0, coupon.code().length() - 16);
        return store.orders(GetMoreOrderQuery.byPrefix(prefix, 1, 50)).items();
    }

    static final class CountingGuard implements GenerateGuard {
        final AtomicInteger counted = new AtomicInteger();
        final AtomicInteger givenBack = new AtomicInteger();

        @Override
        public void beforeGenerate() {
            counted.incrementAndGet();
        }

        @Override
        public void notProcessed() {
            givenBack.incrementAndGet();
        }
    }

    // ---- the rule ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the simulated rule is seeded once, at boot: active cart_fixed, AUTO coupons, unlimited uses")
    void ruleSeededOnce() {
        assertThat(store).isInstanceOf(SimulatedGetMoreStore.class);
        assertThat(store.mode()).isEqualTo(GetMoreMode.SIMULATED);
        assertThat(seeder.seed()).as("already there").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM getmore_sim_rules", Integer.class)).isEqualTo(1);

        GetMoreSalesRule rule = store.salesRule(RULE);
        assertThat(rule.active()).isTrue();
        assertThat(rule.simpleAction()).isEqualTo("cart_fixed");
        assertThat(rule.couponType()).isEqualTo("AUTO");
        assertThat(rule.useAutoGeneration()).isTrue();
        assertThat(rule.usesPerCoupon()).isZero();
        assertThat(rule.toDate()).isNull();
        assertThatThrownBy(() -> store.salesRule(999)).isInstanceOf(GetMoreNotFoundException.class);
    }

    // ---- generate ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("generate: an unknown rule is a 400 (not a 404), a bad spec Magento's input error, and codes only")
    void generateAsMagento() {
        assertThatThrownBy(() -> store.generateCoupons(GetMoreCouponSpec.single(999, newPrefix(), 16)))
                .isInstanceOfSatisfying(GetMoreRejectedException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(400);
                    assertThat(e.renderedMessage())
                            .isEqualTo("Error occurred when generating coupons: No such entity with rule_id = 999");
                });
        assertThatThrownBy(() -> store.generateCoupons(new GetMoreCouponSpec(RULE, 0, 16,
                CouponFormat.ALPHANUM, newPrefix())))
                .isInstanceOfSatisfying(GetMoreRejectedException.class, e -> assertThat(e.renderedMessage())
                        .isEqualTo("One or more input exceptions have occurred."));

        String prefix = newPrefix();
        List<String> codes = store.generateCoupons(GetMoreCouponSpec.single(RULE, prefix, 16));

        assertThat(codes).singleElement().satisfies(code -> {
            assertThat(code).startsWith(prefix).hasSize(prefix.length() + 16);
            assertThat(code.substring(prefix.length())).matches("[A-Z0-9]{16}");
        });
        GetMoreCoupon coupon = store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 1, 50)).items().getFirst();
        assertThat(coupon.timesUsed()).isZero();
        assertThat(coupon.type()).isEqualTo(1);
        assertThat(coupon.expirationDateUtc()).isNull();
        assertThat(coupon.usageLimit()).isZero();
    }

    @Test
    @DisplayName("codes are unique without case: the index refuses a case variant, and a PUT cannot rename onto one")
    void codesUniqueIgnoringCase() {
        String prefix = newPrefix();
        String first = store.generateCoupons(GetMoreCouponSpec.single(RULE, prefix, 16)).getFirst();
        String second = store.generateCoupons(GetMoreCouponSpec.single(RULE, prefix, 16)).getFirst();
        GetMoreCoupon secondCoupon = store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 1, 50)).items()
                .stream().filter(c -> c.code().equals(second)).findFirst().orElseThrow();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO getmore_sim_coupons (rule_id, code, times_used, is_primary,"
                + " type, created_at) VALUES (43, ?, 0, FALSE, 1, now())", first.toLowerCase(Locale.ROOT)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> store.updateCoupon(secondCoupon.couponId(),
                new GetMoreCouponUpdate(RULE, first.toLowerCase(Locale.ROOT), 1, null)))
                .isInstanceOfSatisfying(GetMoreRejectedException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(400);
                    assertThat(e.renderedMessage()).startsWith("Error occurred when saving coupon: ");
                });
    }

    // ---- search -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("search: like reads _ as a wildcard (re-filtered above the port), page size 301 refused, past the end"
            + " empty with the true total")
    void searchAsMagento() {
        String prefix = newPrefix();
        List<String> codes = store.generateCoupons(new GetMoreCouponSpec(RULE, 2, 16,
                CouponFormat.ALPHANUM, prefix));
        assertThat(codes).hasSize(2);
        String wildcard = prefix.substring(0, 5) + "_" + prefix.substring(6);

        assertThat(store.searchCoupons(GetMoreCouponQuery.byPrefix(wildcard, 1, 50)).items())
                .as("the store matches as Magento's LIKE does").hasSize(2);
        assertThat(coupons.onPrefix(wildcard, Deadline.none()).coupons())
                .as("the code above the port keeps only real prefix matches").isEmpty();
        assertThat(store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix.toLowerCase(Locale.ROOT), 1, 50))
                .items()).as("without case").hasSize(2);

        assertThatThrownBy(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 1, 301)))
                .isInstanceOfSatisfying(GetMoreRejectedException.class, e -> assertThat(e.renderedMessage())
                        .isEqualTo("Maximum SearchCriteria pageSize is 300"));

        GetMorePage<GetMoreCoupon> pastTheEnd = store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 3, 1));
        assertThat(pastTheEnd.items()).isEmpty();
        assertThat(pastTheEnd.totalCount()).isEqualTo(2);
        GetMorePage<GetMoreCoupon> second = store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 2, 1));
        assertThat(second.items()).hasSize(1);
        assertThat(second.last()).isTrue();
    }

    // ---- PUT --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("PUT resets the usage limits from the rule, stores the expiry, keeps it when the body omits it, and"
            + " refuses a type the rule does not allow")
    void putAsMagento() {
        String prefix = newPrefix();
        store.generateCoupons(GetMoreCouponSpec.single(RULE, prefix, 16));
        GetMoreCoupon coupon = store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 1, 50)).items().getFirst();
        jdbc.update("UPDATE getmore_sim_coupons SET usage_limit = 5, usage_per_customer = 3 WHERE coupon_id = ?",
                coupon.couponId());
        LocalDateTime expiry = LocalDateTime.of(2026, 11, 13, 21, 59, 59);

        GetMoreCoupon updated = store.updateCoupon(coupon.couponId(),
                new GetMoreCouponUpdate(RULE, coupon.code(), 1, expiry));

        assertThat(updated.usageLimit()).isZero();
        assertThat(updated.usagePerCustomer()).isZero();
        assertThat(updated.expirationDateUtc()).isEqualTo(expiry);
        store.updateCoupon(coupon.couponId(), new GetMoreCouponUpdate(RULE, coupon.code(), 1, null));
        assertThat(store.coupon(coupon.couponId()).expirationDateUtc()).isEqualTo(expiry);

        assertThatThrownBy(() -> store.updateCoupon(coupon.couponId(),
                new GetMoreCouponUpdate(RULE, coupon.code(), 0, expiry)))
                .isInstanceOfSatisfying(GetMoreRejectedException.class, e -> assertThat(e.renderedMessage())
                        .isEqualTo("Error occurred when saving coupon: Specified rule only allows auto generated"
                                + " coupons"));
        assertThatThrownBy(() -> store.updateCoupon(Long.MAX_VALUE,
                new GetMoreCouponUpdate(RULE, coupon.code(), 1, expiry)))
                .isInstanceOfSatisfying(GetMoreRejectedException.class, e -> assertThat(e.httpStatus())
                        .isEqualTo(404));
    }

    // ---- deleteByCodes ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("deleteByCodes: all-or-nothing without ignoreInvalid, a repeat comes back missing, and a code in"
            + " another case is deleted AND reported missing")
    void deleteAsMagento() {
        String prefix = newPrefix();
        String code = store.generateCoupons(GetMoreCouponSpec.single(RULE, prefix, 16)).getFirst();
        long couponId = store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 1, 50)).items().getFirst()
                .couponId();

        assertThatThrownBy(() -> store.deleteCoupons(List.of(code, prefix + "NOSUCHCODE000000"), false))
                .isInstanceOfSatisfying(GetMoreRejectedException.class, e -> assertThat(e.renderedMessage())
                        .isEqualTo("Some coupons are invalid."));
        assertThat(store.coupon(couponId).code()).as("nothing deleted").isEqualTo(code);

        GetMoreCouponDeletion deleted = store.deleteCoupons(List.of(code), true);
        assertThat(deleted.failedItems()).isEmpty();
        assertThat(deleted.missingItems()).isEmpty();
        assertThatThrownBy(() -> store.coupon(couponId)).isInstanceOf(GetMoreNotFoundException.class);
        assertThat(store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 1, 50)).items()).isEmpty();
        assertThat(store.deleteCoupons(List.of(code), true).missingItems()).containsExactly(code);

        String other = store.generateCoupons(GetMoreCouponSpec.single(RULE, newPrefix(), 16)).getFirst();
        String lower = other.toLowerCase(Locale.ROOT);
        GetMoreCouponDeletion caseQuirk = store.deleteCoupons(List.of(lower), true);
        assertThat(caseQuirk.missingItems()).containsExactly(lower);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM getmore_sim_coupons WHERE code = ?",
                Boolean.class, other)).as("deleted all the same").isTrue();
    }

    // ---- the till ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("spent in parts down to 0, each sale an invoiced order with Magento's signs; one cent more refused")
    void salesInPartsDownToZero() {
        GetMoreCoupon coupon = boundCoupon("150.00");

        assertThat(sell(coupon, "45.50").balance()).isEqualByComparingTo("104.50");
        assertThat(sell(coupon, "54.50").balance()).isEqualByComparingTo("50.00");
        SimSale last = sell(coupon, "50.00");
        assertThat(last.balance()).isEqualByComparingTo("0");
        assertThat(last.order()).containsEntry("coupon_code", GetMoreCodes.maskedCode(coupon.code()))
                .containsEntry("discount_amount", new BigDecimal("-50.0000"));
        refusedAs(SimulatorRefusal.SIM_INSUFFICIENT_BALANCE, () -> sell(coupon, "0.01"));

        List<GetMoreOrder> orders = ordersOf(coupon);
        assertThat(orders).hasSize(3).allSatisfy(order -> {
            assertThat(order.discountAmount().signum()).isNegative();
            assertThat(order.discountInvoiced()).isEqualByComparingTo(order.discountAmount());
            assertThat(order.discountRefunded()).isEqualByComparingTo("0");
            assertThat(order.state()).isEqualTo("complete");
            assertThat(order.appliedRuleIds()).containsExactly(RULE);
            assertThat(order.storeName()).isEqualTo("GetMore\nGetMore Avondale\nDefault Store View");
            assertThat(order.incrementId()).matches("SIM\\d{9}");
        });
        assertThat(orders.getFirst().discountAmount()).isEqualByComparingTo("-45.50");
        assertThat(store.coupon(coupon.couponId()).timesUsed()).isEqualTo(3);
    }

    @Test
    @DisplayName("more than is left: refused with what is left in the message")
    void insufficientBalanceRefused() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        sell(coupon, "45.50");

        assertThatThrownBy(() -> sell(coupon, "104.51")).isInstanceOfSatisfying(SimulatorRefusedException.class, e -> {
            assertThat(e.getRefusal()).isEqualTo(SimulatorRefusal.SIM_INSUFFICIENT_BALANCE);
            assertThat(e.getMessage()).isEqualTo("The amount is more than the USD 104.50 left on this coupon");
        });
    }

    @Test
    @DisplayName("spending in parts off: anything but exactly what is left is refused")
    void partialSpendingOffRefused() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        properties.getSimulator().setPartialSpending(false);

        assertThatThrownBy(() -> sell(coupon, "10.00")).isInstanceOfSatisfying(SimulatorRefusedException.class, e -> {
            assertThat(e.getRefusal()).isEqualTo(SimulatorRefusal.SIM_PARTIAL_SPEND_NOT_ALLOWED);
            assertThat(e.getMessage()).isEqualTo("This coupon must be spent in one purchase of USD 150.00");
        });
        assertThat(sell(coupon, "150.00").balance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("spending in parts off: a used coupon is single use, refused as not valid even after a refund")
    void partialSpendingOffIsSingleUse() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        properties.getSimulator().setPartialSpending(false);
        SimSale whole = sell(coupon, "150.00");
        assertThat(till.refund(whole.orderNumber(), new BigDecimal("10.00"), null).balance())
                .as("the store gives the amount back").isEqualByComparingTo("10.00");

        assertThatThrownBy(() -> sell(coupon, "10.00")).isInstanceOfSatisfying(SimulatorRefusedException.class, e -> {
            assertThat(e.getRefusal()).as("Magento's usage-limit answer").isEqualTo(
                    SimulatorRefusal.SIM_COUPON_NOT_FOUND);
            assertThat(e.getMessage()).isEqualTo("The coupon code \"" + GetMoreCodes.maskedCode(coupon.code())
                    + "\" is not valid.");
        });
        assertThat(ordersOf(coupon)).hasSize(1);

        properties.getSimulator().setPartialSpending(true);
        assertThat(sell(coupon, "10.00").balance()).as("in parts, a refund re-opens it (A3)")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a refund is a state-2 credit memo that gives the amount back; nothing invoiced cannot be refunded")
    void refundRestores() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        SimSale first = sell(coupon, "45.50");
        sell(coupon, "54.50");

        SimRefund refund = till.refund(first.orderNumber(), new BigDecimal("10.00"), "AVD-RF-" + first.orderId());

        assertThat(refund.balance()).isEqualByComparingTo("60.00");
        assertThat(refund.creditMemoNumber()).matches("SIMCM\\d{8}");
        assertThat(refund.creditMemo()).containsEntry("state", 2)
                .containsEntry("discount_amount", new BigDecimal("-10.0000"));
        List<GetMoreCreditMemo> memos = store.creditMemos(new GetMoreCreditMemoQuery(List.of(first.orderId()), 1,
                50)).items();
        assertThat(memos).singleElement().satisfies(memo -> {
            assertThat(memo.refunded()).isTrue();
            assertThat(memo.discountAmount()).isEqualByComparingTo("-10.00");
        });
        GetMoreOrder refunded = ordersOf(coupon).getFirst();
        assertThat(refunded.discountRefunded()).isEqualByComparingTo("-10.00");
        assertThat(store.coupon(coupon.couponId()).timesUsed()).as("never down on a refund").isEqualTo(2);
        assertThat(sell(coupon, "60.00").balance()).isEqualByComparingTo("0");

        refusedAs(SimulatorRefusal.SIM_REFUND_TOO_LARGE,
                () -> till.refund(first.orderNumber(), new BigDecimal("35.51"), null));
        SimRefund rest = till.refund(first.orderNumber(), null, null);
        assertThat(rest.amount()).isEqualByComparingTo("35.50");
        assertThat(ordersOf(coupon).getFirst().state()).isEqualTo("closed");
        refusedAs(SimulatorRefusal.SIM_NOTHING_TO_REFUND, () -> till.refund(first.orderNumber(), null, null));
        refusedAs(SimulatorRefusal.SIM_SALE_NOT_FOUND, () -> till.refund("SIM999999999", null, null));
    }

    @Test
    @DisplayName("a held sale: ordered, not invoiced, counts against the balance; invoiced later; once only")
    void heldThenInvoiced() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        SimSale held = till.sell(coupon.code(), new BigDecimal("20.00"), USD, new BigDecimal("31.75"), "GM-AVD",
                null, null, false);

        assertThat(held.balance()).isEqualByComparingTo("130.00");
        GetMoreOrder order = ordersOf(coupon).getFirst();
        assertThat(order.state()).isEqualTo("processing");
        assertThat(order.held()).isEqualByComparingTo("20.00");
        assertThat(order.grandTotal()).isEqualByComparingTo("11.75");
        refusedAs(SimulatorRefusal.SIM_NOTHING_TO_REFUND, () -> till.refund(held.orderNumber(), null, null));

        SimSale invoiced = till.invoice(held.orderNumber());
        assertThat(invoiced.amount()).isEqualByComparingTo("20.00");
        assertThat(ordersOf(coupon).getFirst().held()).isEqualByComparingTo("0");
        assertThat(ordersOf(coupon).getFirst().state()).isEqualTo("complete");
        refusedAs(SimulatorRefusal.SIM_NOTHING_TO_INVOICE, () -> till.invoice(held.orderNumber()));
    }

    @Test
    @DisplayName("an expired code is refused at the till (assumption A4), and off, it is not")
    void expiredCodeRefused() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1).truncatedTo(ChronoUnit.SECONDS);

        GetMoreCoupon expired = till.expire(coupon.code(), past);

        assertThat(expired.expirationDateUtc()).isEqualTo(past);
        refusedAs(SimulatorRefusal.SIM_COUPON_EXPIRED, () -> sell(coupon, "10.00"));
        properties.getSimulator().setEnforceCouponExpiry(false);
        assertThat(sell(coupon, "10.00").balance()).isEqualByComparingTo("140.00");
    }

    @Test
    @DisplayName("expire goes through the PORT's PUT with no transaction open: a PUT fault reaches it, and inside a"
            + " transaction it is refused")
    void expireRunsThroughThePortWithNoTransaction() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        LocalDateTime past = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1).truncatedTo(ChronoUnit.SECONDS);
        faults.inject(Operation.UPDATE_COUPON, SimulatorFaults.FaultKind.UNAVAILABLE, 1);

        assertThatThrownBy(() -> till.expire(coupon.code(), past)).isInstanceOf(GetMoreUnavailableException.class);
        assertThat(store.coupon(coupon.couponId()).expirationDateUtc()).isNull();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> till.expire(coupon.code(), past)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("GetMore is never called with a transaction open");
        assertThat(store.coupon(coupon.couponId()).expirationDateUtc()).isNull();
        refusedAs(SimulatorRefusal.SIM_COUPON_NOT_FOUND, () -> till.expire("SIM-NOSUCH00-0000000000000000", past));
    }

    @Test
    @DisplayName("a sale is idempotent on its reference: the same sale again is a replay; another under it is refused")
    void replayByReference() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        String reference = "AVD-T3-" + coupon.couponId();

        SimSale first = till.sell(coupon.code(), new BigDecimal("45.50"), USD, null, "GM-AVD", null, reference, true);
        SimSale again = till.sell(coupon.code(), new BigDecimal("45.50"), USD, null, "GM-AVD", null, reference, true);

        assertThat(first.replayed()).isFalse();
        assertThat(again.replayed()).isTrue();
        assertThat(again.orderId()).isEqualTo(first.orderId());
        assertThat(again.balance()).isEqualByComparingTo("104.50");
        assertThat(ordersOf(coupon)).hasSize(1);
        refusedAs(SimulatorRefusal.SIM_REFERENCE_REUSED, () -> till.sell(coupon.code(), new BigDecimal("10.00"), USD,
                null, "GM-AVD", null, reference, true));
    }

    @Test
    @DisplayName("refusals: an unknown code, a coupon with no allowance, another currency")
    void otherRefusals() {
        refusedAs(SimulatorRefusal.SIM_COUPON_NOT_FOUND, () -> till.sell("SIM-NOSUCH00-0000000000000000",
                BigDecimal.TEN, null, null, "GM-AVD", null, null, true));
        String unbound = store.generateCoupons(GetMoreCouponSpec.single(RULE, newPrefix(), 16)).getFirst();
        assertThatThrownBy(() -> till.sell(unbound, BigDecimal.TEN, null, null, "GM-AVD", null, null, true))
                .isInstanceOfSatisfying(SimulatorRefusedException.class, e -> {
                    assertThat(e.getRefusal()).isEqualTo(SimulatorRefusal.SIM_COUPON_NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("The coupon code \"" + GetMoreCodes.maskedCode(unbound)
                            + "\" is not valid.").doesNotContain(unbound);
                });
        GetMoreCoupon coupon = boundCoupon("150.00");
        refusedAs(SimulatorRefusal.SIM_CURRENCY_MISMATCH, () -> till.sell(coupon.code(), BigDecimal.TEN, "ZWG", null,
                "GM-AVD", null, null, true));
    }

    @Test
    @DisplayName("the store's currency changed after the coupon was bound: its allowance is never spent in another"
            + " currency")
    void allowanceCurrencyEnforcedAfterAStoreCurrencyChange() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        properties.getSimulator().setCurrency("ZWG");

        assertThatThrownBy(() -> sell(coupon, "10.00")).isInstanceOfSatisfying(SimulatorRefusedException.class, e -> {
            assertThat(e.getRefusal()).isEqualTo(SimulatorRefusal.SIM_CURRENCY_MISMATCH);
            assertThat(e.getMessage()).isEqualTo("This coupon is worth USD, not ZWG");
        });
        refusedAs(SimulatorRefusal.SIM_CURRENCY_MISMATCH, () -> till.sell(coupon.code(), BigDecimal.TEN, USD, null,
                "GM-AVD", null, null, true));
        assertThat(ordersOf(coupon)).isEmpty();

        properties.getSimulator().setCurrency(USD);
        assertThat(sell(coupon, "10.00").balance()).isEqualByComparingTo("140.00");
    }

    @Test
    @DisplayName("a PUT may not rename a coupon that has an allowance or an order (its balance finds its orders by"
            + " code); a fresh one still can be")
    void renameRefusedOnceTheCouponCarriesValue() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        String prefix = coupon.code().substring(0, coupon.code().length() - 16);
        String renamed = prefix + "RENAMED000000000";

        assertThatThrownBy(() -> store.updateCoupon(coupon.couponId(), new GetMoreCouponUpdate(RULE, renamed, 1, null)))
                .as("bound, nothing sold").isInstanceOfSatisfying(GetMoreRejectedException.class,
                        e -> assertThat(e.renderedMessage()).isEqualTo("Error occurred when saving coupon: A simulated"
                                + " coupon keeps its code once it has an allowance or an order."));
        sell(coupon, "45.50");
        assertThatThrownBy(() -> store.updateCoupon(coupon.couponId(), new GetMoreCouponUpdate(RULE, renamed, 1, null)))
                .as("bound and sold").isInstanceOf(GetMoreRejectedException.class);
        assertThat(store.coupon(coupon.couponId()).code()).isEqualTo(coupon.code());
        refusedAs(SimulatorRefusal.SIM_INSUFFICIENT_BALANCE, () -> sell(coupon, "104.51"));

        String fresh = store.generateCoupons(GetMoreCouponSpec.single(RULE, newPrefix(), 16)).getFirst();
        GetMoreCoupon unbound = store.searchCoupons(GetMoreCouponQuery.byCode(fresh, 1, 50)).items().getFirst();
        String freshRenamed = fresh.substring(0, fresh.length() - 16) + "RENAMED000000000";
        assertThat(store.updateCoupon(unbound.couponId(), new GetMoreCouponUpdate(RULE, freshRenamed, 1, null)).code())
                .isEqualTo(freshRenamed);
    }

    @Test
    @DisplayName("a sale keeps the code as the till typed it, in its case (Magento's quote does): read back by prefix,"
            + " counted in the balance, in the activity, and refundable")
    void saleKeepsTheCodeAsTyped() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        String typed = coupon.code().toLowerCase(Locale.ROOT);
        String prefix = coupon.code().substring(0, coupon.code().length() - 16);

        SimSale lower = till.sell(typed, new BigDecimal("45.50"), null, null, "GM-AVD", null, null, true);
        SimSale upper = sell(coupon, "4.50");

        assertThat(upper.balance()).isEqualByComparingTo("100.00");
        List<GetMoreOrder> orders = store.orders(GetMoreOrderQuery.byPrefix(prefix, 1, 50)).items();
        assertThat(orders).extracting(GetMoreOrder::couponCode).containsExactly(typed, coupon.code());
        assertThat(coupons.activity(prefix, Set.of(), Deadline.none()).ordersOnPrefix())
                .extracting(GetMoreOrder::entityId).containsExactly(lower.orderId(), upper.orderId());

        SimRefund refund = till.refund(lower.orderNumber(), new BigDecimal("5.50"), null);
        assertThat(refund.balance()).isEqualByComparingTo("105.50");
        assertThat(till.lookup(coupon.code()).balance()).isEqualByComparingTo("105.50");
        refusedAs(SimulatorRefusal.SIM_INSUFFICIENT_BALANCE, () -> till.sell(typed, new BigDecimal("105.51"), null,
                null, "GM-AVD", null, null, true));
    }

    @Test
    @DisplayName("amounts are cents (assumption A2: from 0.01): a sub-cent or 3-decimal sale, basket or refund is"
            + " refused, and nothing is written")
    void amountsAreCents() {
        GetMoreCoupon coupon = boundCoupon("150.00");

        for (String amount : List.of("0.001", "10.005", "0.0001")) {
            assertThatThrownBy(() -> sell(coupon, amount)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("amount must be at least 0.01 with at most 2 decimals");
        }
        assertThatThrownBy(() -> till.sell(coupon.code(), BigDecimal.TEN, null, new BigDecimal("12.345"), "GM-AVD",
                null, null, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("basketTotal must be at least 0.01 with at most 2 decimals");
        assertThat(ordersOf(coupon)).isEmpty();

        SimSale sale = sell(coupon, "10.50");
        assertThat(sell(coupon, "0.01").balance()).isEqualByComparingTo("139.49");
        assertThatThrownBy(() -> till.refund(sale.orderNumber(), new BigDecimal("0.005"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.creditMemos(new GetMoreCreditMemoQuery(List.of(sale.orderId()), 1, 50)).items()).isEmpty();
    }

    @Test
    @DisplayName("an amount past the store's NUMERIC(12,4) columns (a basket of 100 000 000.00) is refused before"
            + " anything is written, never a numeric overflow")
    void oversizedAmountsRefusedNotOverflowed() {
        GetMoreCoupon coupon = boundCoupon("150.00");

        assertThatThrownBy(() -> till.sell(coupon.code(), BigDecimal.TEN, null, new BigDecimal("100000000.00"),
                "GM-AVD", null, null, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("basketTotal must be at most 99999999.99");
        assertThatThrownBy(() -> sell(coupon, "100000000")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be at most 99999999.99");
        assertThat(ordersOf(coupon)).isEmpty();
        assertThat(till.lookup(coupon.code()).balance()).isEqualByComparingTo("150.00");

        SimSale sale = till.sell(coupon.code(), BigDecimal.TEN, null, new BigDecimal("99999999.99"), "GM-AVD", null,
                null, true);
        assertThat(sale.order()).containsEntry("subtotal", new BigDecimal("99999999.9900"));
        assertThatThrownBy(() -> till.refund(sale.orderNumber(), new BigDecimal("100000000"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("amount must be at most 99999999.99");
        assertThat(store.creditMemos(new GetMoreCreditMemoQuery(List.of(sale.orderId()), 1, 50)).items()).isEmpty();
    }

    @Test
    @DisplayName("the store's own view: unset discount fields absent, codes masked, allowance, balance, assumptions")
    void lookupOmitsUnsetDiscountFields() {
        GetMoreCoupon coupon = boundCoupon("150.00");
        till.sell(coupon.code(), new BigDecimal("20.00"), null, null, "GM-AVD", null, null, false);

        SimLookup held = till.lookup(coupon.code().toLowerCase(Locale.ROOT));

        assertThat(held.coupon()).containsEntry("code", GetMoreCodes.maskedCode(coupon.code()))
                .doesNotContainKey("expiration_date")
                .as("a generated coupon: Magento stores is_primary NULL and renders it null")
                .containsEntry("is_primary", null);
        assertThat(held.rule()).containsEntry("rule_id", RULE).containsEntry("simple_action", "cart_fixed");
        assertThat(held.orders()).containsEntry("total_count", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> order = ((List<Map<String, Object>>) held.orders().get("items")).getFirst();
        assertThat(order).doesNotContainKeys("discount_invoiced", "discount_refunded", "discount_canceled")
                .containsEntry("discount_amount", new BigDecimal("-20.0000"));
        assertThat(held.creditmemos()).containsEntry("total_count", 0);
        assertThat(held.allowance()).isEqualByComparingTo("150.00");
        assertThat(held.allowanceCurrency()).isEqualTo(USD);
        assertThat(held.balance()).isEqualByComparingTo("130.00");
        assertThat(held.withdrawn()).isFalse();
        assertThat(held.assumptions()).containsExactly("A1 per-coupon allowance", "A2 spending in parts",
                "A3 refund restores balance", "A4 coupon expiry enforced");
        assertThat(held.toString()).doesNotContain(coupon.code());

        properties.getSimulator().setPartialSpending(false);
        properties.getSimulator().setEnforceCouponExpiry(false);
        assertThat(till.lookup(coupon.code()).assumptions()).as("the rules the till is applying now")
                .containsExactly("A1 per-coupon allowance", "A2 one purchase of the whole balance",
                        "A3 a refund does not re-open a single-use coupon", "A4 coupon expiry not enforced");
        properties.getSimulator().setPartialSpending(true);
        properties.getSimulator().setEnforceCouponExpiry(true);

        String orderNumber = (String) order.get("increment_id");
        till.invoice(orderNumber);
        till.refund(orderNumber, new BigDecimal("5.00"), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = ((List<Map<String, Object>>) till.lookup(coupon.code()).orders().get("items"))
                .getFirst();
        assertThat(after).containsKeys("discount_invoiced", "discount_refunded").doesNotContainKey("discount_canceled");

        store.deleteCoupons(List.of(coupon.code()), true);
        SimLookup withdrawn = till.lookup(coupon.code());
        assertThat(withdrawn.withdrawn()).isTrue();
        assertThat(withdrawn.coupon()).isNull();
        assertThat(withdrawn.orders()).containsEntry("total_count", 1);
    }

    // ---- concurrency ------------------------------------------------------------------------------------------

    /** Runs both at once (behind a latch); each outcome, a result or the exception it threw. */
    private static List<Object> together(Callable<Object> first, Callable<Object> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> each : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try {
                        return each.call();
                    } catch (Exception failed) {
                        return failed;
                    }
                }));
            }
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("two tills spending one coupon at once never take more than its allowance")
    void twoConcurrentSellsNeverExceedAllowance() throws Exception {
        GetMoreCoupon coupon = boundCoupon("100.00");

        List<Object> outcomes = together(() -> sell(coupon, "60.00"), () -> sell(coupon, "60.00"));

        assertThat(outcomes).filteredOn(SimSale.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(SimulatorRefusedException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((SimulatorRefusedException) e).getRefusal())
                        .isEqualTo(SimulatorRefusal.SIM_INSUFFICIENT_BALANCE));
        assertThat(ordersOf(coupon)).hasSize(1);
    }

    @Test
    @DisplayName("two first sales at a new outlet at once both succeed, and make one outlet")
    void concurrentFirstSalesAtOneOutlet() throws Exception {
        GetMoreCoupon one = boundCoupon("100.00");
        GetMoreCoupon two = boundCoupon("100.00");
        String outlet = "GM-NEW-" + one.couponId();

        List<Object> outcomes = together(
                () -> till.sell(one.code(), BigDecimal.TEN, null, null, outlet, "GetMore New", null, true),
                () -> till.sell(two.code(), BigDecimal.TEN, null, null, outlet, "GetMore New", null, true));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(SimSale.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM getmore_sim_stores WHERE outlet_code = ?", Integer.class,
                outlet)).isEqualTo(1);
        assertThat(ordersOf(one).getFirst().storeId()).isEqualTo(ordersOf(two).getFirst().storeId());
    }

    @Test
    @DisplayName("one reference used by two sales at once: one sells, the other is SIM_REFERENCE_REUSED, never a 500")
    void concurrentSameReferenceIsReusedNot500() throws Exception {
        GetMoreCoupon one = boundCoupon("100.00");
        GetMoreCoupon two = boundCoupon("100.00");
        String reference = "AVD-SAME-" + one.couponId();

        List<Object> outcomes = together(
                () -> till.sell(one.code(), BigDecimal.TEN, null, null, "GM-AVD", null, reference, true),
                () -> till.sell(two.code(), BigDecimal.TEN, null, null, "GM-AVD", null, reference, true));

        assertThat(outcomes).filteredOn(SimSale.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(SimulatorRefusedException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((SimulatorRefusedException) e).getRefusal())
                        .isEqualTo(SimulatorRefusal.SIM_REFERENCE_REUSED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM getmore_sim_orders WHERE sale_reference = ?",
                Integer.class, reference)).isEqualTo(1);
    }

    // ---- faults -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("LOSE_RESPONSE on generate: the coupon is minted and committed, the answer lost; find-or-create keeps"
            + " the count and adopts it next time without generating again")
    void loseResponseFaultCommitsThenThrows() {
        String prefix = newPrefix();
        CountingGuard guard = new CountingGuard();
        faults.inject(Operation.GENERATE, SimulatorFaults.FaultKind.LOSE_RESPONSE, 1);

        assertThatThrownBy(() -> coupons.findOrCreate(request(prefix, "150.00", guard), Deadline.none()))
                .isInstanceOf(GetMoreOutcomeUnknownException.class);
        assertThat(guard.counted).hasValue(1);
        assertThat(guard.givenBack).hasValue(0);
        assertThat(store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 1, 50)).items())
                .as("committed before the answer was lost").hasSize(1);

        FindOrCreate again = coupons.findOrCreate(request(prefix, "150.00", guard), Deadline.none());

        assertThat(again).isInstanceOfSatisfying(FindOrCreate.Bound.class, bound -> {
            assertThat(bound.adopted()).isTrue();
            assertThat(sell(bound.coupon(), "150.00").balance()).isEqualByComparingTo("0");
        });
        assertThat(guard.counted).as("never generated again").hasValue(1);
        assertThat(store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 1, 50)).items()).hasSize(1);
    }

    @Test
    @DisplayName("UNAVAILABLE on generate: nothing minted, and find-or-create gives the count back")
    void unavailableFaultGivesTheCountBack() {
        String prefix = newPrefix();
        CountingGuard guard = new CountingGuard();
        faults.inject(Operation.GENERATE, SimulatorFaults.FaultKind.UNAVAILABLE, 1);

        assertThatThrownBy(() -> coupons.findOrCreate(request(prefix, "150.00", guard), Deadline.none()))
                .isInstanceOf(GetMoreUnavailableException.class);
        assertThat(guard.counted).hasValue(1);
        assertThat(guard.givenBack).hasValue(1);
        assertThat(store.searchCoupons(GetMoreCouponQuery.byPrefix(prefix, 1, 50)).items()).isEmpty();
    }

    @Test
    @DisplayName("the allowance binds once: the same value again is nothing, another is refused")
    void allowanceBindsOnce() {
        String prefix = newPrefix();
        assertThat(coupons.findOrCreate(request(prefix, "150.00", new CountingGuard()), Deadline.none()))
                .isInstanceOf(FindOrCreate.Bound.class);
        assertThat(coupons.findOrCreate(request(prefix, "150.00", new CountingGuard()), Deadline.none()))
                .isInstanceOf(FindOrCreate.Bound.class);

        assertThatThrownBy(() -> coupons.findOrCreate(request(prefix, "100.00", new CountingGuard()), Deadline.none()))
                .isInstanceOfSatisfying(GetMoreRejectedException.class, e -> assertThat(e.reason())
                        .isEqualTo(GetMoreRejectedException.Reason.VALUE_UNSUPPORTED));
    }
}
