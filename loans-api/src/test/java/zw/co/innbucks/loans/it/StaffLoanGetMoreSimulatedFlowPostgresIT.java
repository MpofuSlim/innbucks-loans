package zw.co.innbucks.loans.it;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.simulator.SimRefund;
import zw.co.innbucks.loans.core.getmore.simulator.SimSale;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedTill;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorFaults;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanProperties;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer;
import zw.co.innbucks.loans.core.staff.loan.UnredeemedVoucherTreatment;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.core.voucher.GetMoreDispatcher;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncTrigger;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherSync;
import zw.co.innbucks.loans.core.voucher.VoucherExpiryJob;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Staff Grocery Loan paid out as a GetMore voucher, end to end on staging's shape: SIMULATED, test assertions on, the
 * jobs off, real HTTP and real tokens, against real Postgres. A borrower signs in with a test assertion, applies,
 * quotes and accepts, and the loan is DISBURSED as a SIM- coupon in the same answer; the test actions spend it in
 * parts and refund some, and every reader (the tile, the spends, the portal) shows the same balance; a till of loans
 * refuses the code; the simulator expires it. Then the hard cases: a generate whose answer is lost, adopted on a later
 * read without a second coupon; a paid-out loan cancelled with its coupon; one with a sale held at the till refused;
 * and two syncs at once recording each sale and refund once. Last, payday: the statement before and after the payday
 * sync, the debt that stands and the loan reduced to what was spent, finalising it, and a refund recorded after that
 * landing late in the next payday.
 *
 * <p>GetMore work a read queues runs on the reading thread here (a {@code GetMoreDispatcher} on {@code Runnable::run}),
 * so what a read moved on is in place when the next request is sent, and the clock is a {@link SettableClock}, so a
 * back-off is stepped past rather than slept through.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "it"})
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = {
        "loans.getmore.mode=SIMULATED",
        "loans.getmore.simulator.api-key=" + StaffLoanGetMoreSimulatedFlowPostgresIT.SIMULATOR_KEY,
        "loans.vouchers.code-hmac-key=it-voucher-hmac-key-0123456789abcdef0123456789",
        "loans.vouchers.code-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "loans.borrower.test-assertions.enabled=true",
        "loans.borrower.test-assertions.api-key=" + StaffLoanGetMoreSimulatedFlowPostgresIT.ASSERTION_KEY,
        // Small, so a form body Tomcat parsed itself would fail visibly (a 500) with a body of a few KiB.
        "server.tomcat.max-http-form-post-size=4KB"})
class StaffLoanGetMoreSimulatedFlowPostgresIT {

    static final String SIMULATOR_KEY = "it-flow-sim-7c1e9a3f5b2d4e6a8c0f1b3d5e7a9c2e4b6d";
    static final String ASSERTION_KEY = "it-flow-assertions-5d2f8a1c7e3b9d4f6a0c2e8b";
    private static final String JOURNEY = "/lending/v1/borrower/staff-grocery-loan";
    private static final String SIMULATOR = "/lending/v1/getmore-simulator";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = ItPostgres.createDatabase("loans_getmore_flow_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ItPostgres::user);
        registry.add("spring.datasource.password", ItPostgres::password);
        String privateKey = testAssertionKey();
        registry.add("loans.borrower.test-assertions.private-key", () -> privateKey);
    }

    /** The key loans signs test assertions with here: a new one per run, PKCS#8 in base64, as the env var takes it. */
    private static String testAssertionKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("RSA is not available", impossible);
        }
    }

    static final SettableClock CLOCK = new SettableClock();

    @TestConfiguration
    static class Inline {

        @Bean
        @Primary
        MarketTimeZone settableMarketTimeZone() {
            return new MarketTimeZone("ZW", CLOCK);
        }

        @Bean
        @Primary
        GetMoreDispatcher inlineGetMoreDispatcher(GetMoreVoucherSync sync, StaffLoanVoucherIssuer issuer,
                                                  VoucherRepository vouchers, GetMoreProperties properties,
                                                  MarketTimeZone marketTimeZone) {
            return new GetMoreDispatcher(sync, issuer, vouchers, properties, marketTimeZone, Runnable::run);
        }
    }

    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationContext context;
    @Autowired private SimulatedTill till;
    @Autowired private SimulatorFaults faults;
    @Autowired private GetMoreVoucherSync sync;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private GetMoreProperties properties;
    @Autowired private StaffLoanProperties staffLoanProperties;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final JsonMapper json = JsonMapper.builder().build();

    /** A register member who can borrow, and the phone they sign in with. */
    private record Member(long id, String employeeNumber, String phone) {
    }

    private record Accepted(String reference, long loanId, long voucherIdOrZero, JsonNode loan) {
    }

    @BeforeEach
    void register() {
        if (jdbc.queryForObject("SELECT count(*) FROM staff_register_reconciliations", Integer.class) == 0) {
            jdbc.update("""
                    INSERT INTO staff_register_reconciliations (file_name, file_sha256, run_by, run_at, compared_fields,
                        payroll_rows, register_members, matched, different, left_on_payroll, left_on_payroll_eligible,
                        not_on_register, not_on_payroll, not_on_payroll_eligible, duplicates_on_payroll,
                        unreadable_rows)
                    VALUES ('payroll-master.csv', repeat('a', 64), 'hr', now(), '', 5, 5, 5, 0, 0, 0, 0, 0, 0, 0, 0)
                    """);
            jdbc.update("""
                    INSERT INTO staff_grade_limit_changes (grade, score_band, maximum_limit, effective_from, status,
                                                           proposed_by, proposed_at, decided_by, decided_at)
                    VALUES ('C4', 'ANY', 300.00, '2026-01-01', 'APPROVED', 'maker', now(), 'checker', now())
                    """);
            jdbc.update("""
                    INSERT INTO instrument_templates (instrument_type, version, title, body, published_by, published_at)
                    VALUES ('STAFF_GROCERY_LOAN_AGREEMENT', 1, 'Staff Grocery Loan Agreement',
                            'I, {{fullName}}, borrow {{currency}} {{amount}} as a GetMore Groceries voucher.', 'it',
                            now())
                    """);
        }
    }

    @AfterEach
    void clearFaults() {
        faults.clear();
    }

    // ---- the journey ------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("no job runs here: the voucher expiry job is not a bean")
    void jobsOff() {
        assertThat(context.getBeanNamesForType(VoucherExpiryJob.class)).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("accept, spend in parts, refund, read it everywhere, refused at loans' till, expired")
    void journey() throws Exception {
        Member rutendo = member("E1064", "263771640064");

        // 1. Signed in, applied, quoted and accepted: DISBURSED as a SIM- coupon in the same answer.
        Accepted accepted = borrow(rutendo);
        JsonNode loan = accepted.loan();
        assertThat(loan.path("status").asString()).isEqualTo("DISBURSED");
        String code = loan.path("voucher").path("code").asString();
        assertThat(code).matches("SIM-[0-9A-Z]{8}-[0-9A-Z]{16}");
        assertThat(loan.path("voucher").path("store").asString()).isEqualTo("GETMORE");
        assertThat(loan.path("voucher").path("balance").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(loan.path("voucher").path("simulated").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM getmore_sim_coupons WHERE code LIKE ?", Integer.class,
                code.substring(0, 13) + "%")).isEqualTo(1);
        long voucherId = accepted.voucherIdOrZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE event_type = 'VOUCHER_ISSUED' AND"
                + " entity_id = ?", Integer.class, String.valueOf(voucherId))).isEqualTo(1);

        // 2. Spent in two parts; a third over what is left is refused.
        JsonNode first = simulator("POST", "/sales", sale(accepted.reference(), "45.50", "AVD-T3-000981", true),
                201);
        assertThat(first.path("message").asString())
                .isEqualTo("Sale %s: USD 45.50 off the voucher; USD 104.50 left",
                        first.path("data").path("store").path("order").path("increment_id").asString());
        assertThat(first.path("data").path("voucher").path("balance").decimalValue()).isEqualByComparingTo("104.50");
        assertThat(first.path("data").path("store").path("order").path("coupon_code").asString())
                .as("the store's answer masks the code").isEqualTo("****" + code.substring(code.length() - 4));
        String firstOrder = first.path("data").path("store").path("order").path("increment_id").asString();
        assertThat(simulator("POST", "/sales", sale(accepted.reference(), "45.50", "AVD-T3-000981", true), 200)
                .path("data").path("store").path("replayed").asBoolean()).as("the same reference: a replay").isTrue();
        simulator("POST", "/sales", sale(accepted.reference(), "54.50", "AVD-T3-000994", true), 201);
        JsonNode over = simulator("POST", "/sales", sale(accepted.reference(), "60.00", "AVD-T3-001002", true), 409);
        assertThat(over.path("code").asString()).isEqualTo("SIM_INSUFFICIENT_BALANCE");

        // 3. USD 10.00 of the first sale refunded.
        JsonNode refunded = simulator("POST", "/refunds", """
                { "orderNumber": "%s", "amount": 10.00, "reference": "AVD-RF-0012" }""".formatted(firstOrder), 201);
        assertThat(refunded.path("data").path("voucher").path("balance").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(refunded.path("data").path("voucher").path("refundedAmount").decimalValue())
                .isEqualByComparingTo("10.00");

        // 4. The tile, the spends and the portal agree.
        String token = signIn(rutendo);
        JsonNode tile = borrower("GET", JOURNEY, token, null, 200).path("data").path("loan");
        assertThat(tile.path("voucher").path("balance").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(tile.path("voucher").path("spent").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(tile.path("statusMessage").asString()).isEqualTo("You have USD 60.00 left to spend at GetMore"
                + " Groceries.");
        JsonNode spends = borrower("GET", JOURNEY + "/loans/" + accepted.reference() + "/voucher-spends", token, null,
                200).path("data");
        assertThat(spends.path("movements").size()).isEqualTo(3);
        assertThat(spends.path("movements").get(2).path("kind").asString()).isEqualTo("REFUND");
        assertThat(spends.path("movements").get(2).path("balanceAfter").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(spends.path("balance").decimalValue()).isEqualByComparingTo("60.00");
        JsonNode detail = portal("GET", "/lending/v1/vouchers/" + voucherId, UserGroup.SUPER_ADMIN, null, 200)
                .path("data");
        assertThat(detail.path("redemptions").size()).isEqualTo(2);
        assertThat(detail.path("refunds").size()).isEqualTo(1);
        assertThat(detail.path("voucher").path("balance").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(detail.toString()).as("the portal never shows the code").doesNotContain(code);

        // 5. Loans' own tills do not take a GetMore code.
        JsonNode refusedAtTill = portal("POST", "/lending/v1/voucher-validations", UserGroup.MERCHANT_TILL,
                "{ \"code\": \"" + code + "\", \"outletId\": \"GM-AVD-01\" }", 400);
        assertThat(refusedAtTill.path("code").asString()).isEqualTo("INVALID_VOUCHER_CODE");

        // 6. Expired now by the simulator: EXPIRED, stopped, its coupon withdrawn.
        JsonNode expired = simulator("POST", "/coupon-expiries",
                "{ \"loanReference\": \"" + accepted.reference() + "\", \"reason\": \"test lapse\" }", 200);
        assertThat(expired.path("message").asString()).isEqualTo("Voucher " + voucherId + " expired now (simulation)"
                + " with USD 60.00 unspent");
        assertThat(expired.path("data").path("voucher").path("status").asString()).isEqualTo("EXPIRED");
        assertThat(expired.path("data").path("voucher").path("stoppedReason").asString())
                .isEqualTo("SIMULATOR_EXPIRED");
        assertThat(expired.path("data").path("couponWithdrawn").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT coupon_withdrawn_at IS NOT NULL FROM vouchers WHERE id = ?",
                Boolean.class, voucherId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE event_type LIKE 'GETMORE_SIMULATOR_%'"
                + " AND detail LIKE ?", Integer.class, "%" + code + "%")).as("no audit row holds the code").isZero();
    }

    @Test
    @Order(3)
    @DisplayName("the generate's answer lost: accepted AWAITING, then a later read adopts the coupon, never a second")
    void lostGenerateAnswerAdoptedOnRead() throws Exception {
        Member tariro = member("E1071", "263771640071");
        simulator("POST", "/faults", "{ \"operation\": \"GENERATE\", \"kind\": \"LOSE_RESPONSE\", \"times\": 1 }",
                201);

        Accepted accepted = borrow(tariro);
        assertThat(accepted.loan().path("status").asString()).isEqualTo("AWAITING_DISBURSEMENT");
        assertThat(accepted.loan().path("voucherIssuance").asString()).isEqualTo("PENDING");
        Map<String, Object> issuance = issuance(accepted.loanId());
        assertThat(issuance).containsEntry("status", "PENDING").containsEntry("generate_calls", 1);
        String prefix = (String) issuance.get("coupon_prefix");
        assertThat(couponsOn(prefix)).as("made at the store, its answer lost").isEqualTo(1);

        CLOCK.advance(Duration.ofSeconds(31));
        JsonNode tile = borrower("GET", JOURNEY, signIn(tariro), null, 200).path("data").path("loan");

        assertThat(tile.path("status").asString()).isEqualTo("DISBURSED");
        assertThat(tile.path("voucher").path("code").asString()).startsWith(prefix);
        assertThat(issuance(accepted.loanId())).containsEntry("status", "ISSUED").containsEntry("generate_calls", 1);
        assertThat(couponsOn(prefix)).isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("a paid-out loan whose voucher was never used is cancelled with its voucher, the coupon withdrawn")
    void cancelUntouched() throws Exception {
        Accepted accepted = borrow(member("E1072", "263771640072"));
        String prefix = (String) issuance(accepted.loanId()).get("coupon_prefix");

        JsonNode cancelled = portal("POST", "/lending/v1/staff-loans/" + accepted.loanId() + "/cancel",
                UserGroup.CREDIT_MANAGER, "{ \"reason\": \"Accepted in error\" }", 200);

        assertThat(cancelled.path("message").asString())
                .isEqualTo("Cancelled. The voucher's coupon is withdrawn at GetMore.");
        assertThat(cancelled.path("data").path("status").asString()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForMap("SELECT status, coupon_withdrawn_at FROM vouchers WHERE id = ?",
                accepted.voucherIdOrZero())).containsEntry("status", "CANCELLED")
                .extractingByKey("coupon_withdrawn_at").isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM getmore_sim_coupons WHERE code LIKE ? AND deleted_at IS"
                + " NULL", Integer.class, prefix + "%")).isZero();
    }

    @Test
    @Order(5)
    @DisplayName("a sale held at the till (not invoiced) keeps the loan: the cancel is a 409")
    void cancelRefusedWithAHeldSale() throws Exception {
        Accepted accepted = borrow(member("E1073", "263771640073"));
        JsonNode held = simulator("POST", "/sales", sale(accepted.reference(), "20.00", "AVD-T3-002001", false), 201);
        assertThat(held.path("data").path("voucher").path("heldAmount").decimalValue()).isEqualByComparingTo("20.00");

        JsonNode refused = portal("POST", "/lending/v1/staff-loans/" + accepted.loanId() + "/cancel",
                UserGroup.CREDIT_MANAGER, "{ \"reason\": \"Accepted in error\" }", 409);

        assertThat(refused.path("message").asString()).contains("USD 20.00 held at the till");
        assertThat(jdbc.queryForObject("SELECT status FROM staff_loans WHERE id = ?", String.class,
                accepted.loanId())).isEqualTo("DISBURSED");
    }

    @Test
    @Order(6)
    @DisplayName("two syncs of one voucher at once record each sale and each refund exactly once")
    void parallelSyncs() throws Exception {
        Accepted accepted = borrow(member("E1074", "263771640074"));
        String code = accepted.loan().path("voucher").path("code").asString();
        SimSale sale = till.sell(code, new BigDecimal("30.00"), null, null, "GM-AVD", "GetMore Avondale",
                "AVD-T3-003001", true);
        till.refund(sale.orderNumber(), new BigDecimal("5.00"), "AVD-RF-0031");

        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> syncNow = () -> {
            start.await();
            return sync.sync(accepted.voucherIdOrZero(), GetMoreSyncTrigger.OPERATOR).outcome();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> both = List.of(pool.submit(syncNow), pool.submit(syncNow));
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> one : both) {
                outcomes.add(one.get(30, TimeUnit.SECONDS));
            }
            // The voucher's lock serialises them: one records the sale and the refund, the other then finds nothing
            // new (or, past the lock's wait, is turned away BUSY), never a second copy of either.
            assertThat(outcomes).as("one sync records, the other has nothing left to record")
                    .containsOnlyOnce(GetMoreSyncResult.Outcome.SYNCED)
                    .hasSize(2)
                    .allMatch(outcome -> outcome == GetMoreSyncResult.Outcome.SYNCED
                            || outcome == GetMoreSyncResult.Outcome.UNCHANGED
                            || outcome == GetMoreSyncResult.Outcome.BUSY);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_redemptions WHERE voucher_id = ?", Integer.class,
                accepted.voucherIdOrZero())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_refunds WHERE voucher_id = ?", Integer.class,
                accepted.voucherIdOrZero())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT redeemed_amount FROM vouchers WHERE id = ?", BigDecimal.class,
                accepted.voucherIdOrZero())).isEqualByComparingTo("25.00");
    }

    @Test
    @Order(7)
    @DisplayName("GET /getmore counts this host's store only, in the database: issuances, sync issues, held vouchers")
    void statusCountsThisStore() throws Exception {
        JsonNode status = portal("GET", "/lending/v1/getmore", UserGroup.CREDIT_MANAGER, null, 200).path("data");

        assertThat(status.path("mode").asString()).isEqualTo("SIMULATED");
        assertThat(status.path("heldVouchers").asLong()).as("the sale held at the till in step 5")
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM vouchers WHERE store_mode = 'SIMULATED'"
                        + " AND held_amount > 0", Long.class)).isPositive();
        assertThat(status.path("openSyncIssues").asLong()).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM"
                + " voucher_sync_issues i JOIN vouchers v ON v.id = i.voucher_id WHERE i.cleared_at IS NULL"
                + " AND v.store_mode = 'SIMULATED'", Long.class));
        assertThat(status.path("issuances").path("CANCELLING").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM staff_loan_voucher_issuances WHERE status = 'CANCELLING'"
                        + " AND store_mode = 'SIMULATED'", Long.class));

        // The list behind that count, over the same store, in the database: open ones, then every one.
        JsonNode open = portal("GET", "/lending/v1/voucher-sync-issues", UserGroup.CREDIT_MANAGER, null, 200)
                .path("data");
        assertThat(open.path("totalItems").asLong()).isEqualTo(status.path("openSyncIssues").asLong());
        JsonNode every = portal("GET", "/lending/v1/voucher-sync-issues?open=false", UserGroup.CREDIT_MANAGER, null,
                200).path("data");
        assertThat(every.path("totalItems").asLong()).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM"
                + " voucher_sync_issues i JOIN vouchers v ON v.id = i.voucher_id WHERE v.store_mode = 'SIMULATED'",
                Long.class));
    }

    @Test
    @Order(8)
    @DisplayName("a form or multipart body with the key, chunked and over 8 KiB: 415 unread, never parsed, never a 500")
    void notJsonRefusedUnread() throws Exception {
        int orders = jdbc.queryForObject("SELECT count(*) FROM getmore_sim_orders", Integer.class);
        // Over the cap, and over the limit Tomcat applies to a form it parses itself (4 KB here, 2 MB in the cell): a
        // form that reached that parsing would be a 500, and one that reached the capped stream a 413. Only the
        // filter's refusal, before either, is a 415.
        byte[] big = ("outletName=" + "x".repeat(16 * 1024)).getBytes(StandardCharsets.UTF_8);
        for (String type : List.of("application/x-www-form-urlencoded", "multipart/form-data; boundary=sim")) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(uri(SIMULATOR + "/sales"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("X-Api-Key", SIMULATOR_KEY).header("Content-Type", type)
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big))));

            assertThat(response.statusCode()).as("%s -> %s", type, response.body()).isEqualTo(415);
            assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM getmore_sim_orders", Integer.class))
                .as("the till was never asked").isEqualTo(orders);
    }

    @Test
    @Order(9)
    @DisplayName("spending in parts off: a used coupon is single use at the till too, so a refund re-opens nothing")
    void singleUseWithPartialSpendingOff() throws Exception {
        properties.getSimulator().setPartialSpending(false);
        try {
            Accepted accepted = borrow(member("E1075", "263771640075"));
            JsonNode sold = simulator("POST", "/sales", sale(accepted.reference(), "150.00", "AVD-T3-005001", true),
                    201);
            String order = sold.path("data").path("store").path("order").path("increment_id").asString();
            JsonNode refunded = simulator("POST", "/refunds", """
                    { "orderNumber": "%s", "amount": 10.00, "reference": "AVD-RF-0051" }""".formatted(order), 201);
            assertThat(refunded.path("data").path("voucher").path("status").asString()).isEqualTo("EXPIRED");
            assertThat(refunded.path("data").path("assumptions").toString())
                    .contains("A3 a refund does not re-open a single-use coupon");

            JsonNode refused = simulator("POST", "/sales", sale(accepted.reference(), "10.00", "AVD-T3-005002",
                    true), 404);

            assertThat(refused.path("code").asString()).isEqualTo("SIM_COUPON_NOT_FOUND");
            assertThat(jdbc.queryForMap("SELECT status, stopped_reason, redeemed_amount FROM vouchers WHERE id = ?",
                    accepted.voucherIdOrZero())).containsEntry("status", "EXPIRED")
                    .containsEntry("stopped_reason", "SINGLE_USE_EXHAUSTED")
                    .satisfies(row -> assertThat((BigDecimal) row.get("redeemed_amount"))
                            .isEqualByComparingTo("140.00"));
            assertThat(jdbc.queryForList("SELECT kind FROM voucher_sync_issues WHERE voucher_id = ?", String.class,
                    accepted.voucherIdOrZero())).contains("COUPON_EXHAUSTED").doesNotContain("LATE_SPEND");
        } finally {
            properties.getSimulator().setPartialSpending(true);
        }
    }

    // ---- payday ---------------------------------------------------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("payday: what each loan owes at the cut-off, the payday sync, finalising, and a refund recorded late")
    void payday() throws Exception {
        // A loan reduced to what is spent: 100.00 spent, 10.00 back, expired by the simulator with 60.00 unspent.
        staffLoanProperties.setUnredeemedVoucherTreatment(UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT);
        Accepted reduced;
        try {
            reduced = borrow(member("E1076", "263771640076"));
        } finally {
            staffLoanProperties.setUnredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS);
        }
        JsonNode sold = simulator("POST", "/sales", sale(reduced.reference(), "45.50", "AVD-T3-007001", true), 201);
        String soldOrder = sold.path("data").path("store").path("order").path("increment_id").asString();
        simulator("POST", "/sales", sale(reduced.reference(), "54.50", "AVD-T3-007002", true), 201);
        simulator("POST", "/refunds", """
                { "orderNumber": "%s", "amount": 10.00, "reference": "AVD-RF-0071" }""".formatted(soldOrder), 201);
        simulator("POST", "/coupon-expiries",
                "{ \"loanReference\": \"" + reduced.reference() + "\", \"reason\": \"test lapse\" }", 200);

        LocalDate payday = jdbc.queryForObject("SELECT due_date FROM staff_loans WHERE employee_number = 'E1064'",
                LocalDate.class);
        assertThat(jdbc.queryForObject("SELECT due_date FROM staff_loans WHERE id = ?", LocalDate.class,
                reduced.loanId())).isEqualTo(payday);
        String statement = "/lending/v1/staff-loan-collections/" + payday;

        // Before the cut-off: a preview, every voucher read before it, and nothing can be finalised yet.
        JsonNode early = portal("GET", statement, UserGroup.FINANCE, null, 200);
        assertThat(early.path("message").asString()).isEqualTo("SIMULATED: not for payroll");
        assertThat(lineOf(early, "E1064").path("flags").toString()).contains("STALE");
        assertThat(portal("POST", statement + "/finalisation", UserGroup.FINANCE, null, 409).path("message")
                .asString()).startsWith("The cut-off has not passed yet");

        // 10:00 on payday in Harare: the payday sync reads every voucher that can fall in the cycle, page by page.
        LocalDateTime cutoff = LocalDateTime.ofInstant(payday.atStartOfDay(ZoneId.of("Africa/Harare"))
                .toInstant(), ZoneOffset.UTC);
        CLOCK.advance(Duration.between(CLOCK.instant(), cutoff.plusHours(8).toInstant(ZoneOffset.UTC)));
        int unavailable = 0;
        long after = 0;
        int pages = 0;
        do {
            JsonNode page = portal("POST", statement + "/getmore-syncs?after=" + after + "&limit=2",
                    UserGroup.FINANCE, null, 200).path("data");
            unavailable += page.path("unavailable").asInt();
            after = page.path("nextAfter").isNull() ? -1 : page.path("nextAfter").asLong();
            pages++;
        } while (after >= 0 && pages < 20);
        assertThat(after).as("walked to the end").isEqualTo(-1);
        assertThat(unavailable).isZero();

        JsonNode report = portal("GET", statement, UserGroup.CREDIT_MANAGER, null, 200).path("data");
        assertThat(report.path("simulated").asBoolean()).isTrue();
        assertThat(report.path("merchantSettlement").path("staleVouchers").asInt()).isZero();
        JsonNode debt = lineOf(report, "E1064");
        assertThat(debt.path("treatment").asString()).isEqualTo("DEBT_STANDS");
        assertThat(debt.path("voucherStateAtCutoff").asString()).isEqualTo("EXPIRED");
        assertThat(debt.path("stoppedReason").asString()).isEqualTo("SIMULATOR_EXPIRED");
        assertThat(debt.path("spentNet").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(debt.path("amountToCollect").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(debt.path("final").asBoolean()).isTrue();
        JsonNode spentOnly = lineOf(report, "E1076");
        assertThat(spentOnly.path("treatment").asString()).isEqualTo("REDUCED_TO_AMOUNT_SPENT");
        assertThat(spentOnly.path("amountToCollect").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(spentOnly.path("final").asBoolean()).as("final once expired").isTrue();
        JsonNode held = lineOf(report, "E1073");
        assertThat(held.path("final").asBoolean()).isFalse();
        assertThat(held.path("flags").toString()).contains("HELD_SALES");
        assertThat(report.toString()).as("a cancelled loan owes nothing and is not listed")
                .doesNotContain("\"employeeNumber\":\"E1072\"");
        BigDecimal unsettledNet = jdbc.queryForObject("""
                SELECT coalesce((SELECT sum(amount) FROM voucher_redemptions WHERE source = 'GETMORE_SYNC'
                                   AND store_mode = 'SIMULATED' AND settlement_cycle IS NULL AND redeemed_at < ?), 0)
                     - coalesce((SELECT sum(amount) FROM voucher_refunds WHERE store_mode = 'SIMULATED'
                                   AND settlement_cycle IS NULL AND refunded_at < ?), 0)
                """, BigDecimal.class, cutoff, cutoff);
        assertThat(report.path("merchantSettlement").path("totals").get(0).path("net").decimalValue())
                .isEqualByComparingTo(unsettledNet);

        // The sale still held at the till is not final: refused without acceptNonFinal, finalised with it.
        assertThat(portal("POST", statement + "/finalisation", UserGroup.FINANCE, null, 409).path("message")
                .asString()).startsWith("1 line is not final and 0 vouchers are stale");
        portal("POST", statement + "/finalisation", UserGroup.CREDIT_MANAGER, null, 403);
        JsonNode finalised = portal("POST", statement + "/finalisation", UserGroup.FINANCE,
                "{ \"acceptNonFinal\": true, \"reason\": \"E1073's held sale is collected next month\" }", 200);
        assertThat(finalised.path("message").asString()).startsWith("Collections for ")
                .endsWith("(SIMULATED: not for payroll)");
        assertThat(finalised.path("data").path("finalised").asBoolean()).isTrue();
        assertThat(jdbc.queryForMap("SELECT store_mode, non_final_lines, stale_vouchers, reason FROM"
                + " staff_loan_collection_cycles WHERE payday = ?", payday))
                .containsEntry("store_mode", "SIMULATED").containsEntry("non_final_lines", 1)
                .containsEntry("stale_vouchers", 0)
                .containsEntry("reason", "E1073's held sale is collected next month");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_loan_collection_lines WHERE payday = ?",
                Integer.class, payday)).isEqualTo(finalised.path("data").path("lines").size());
        assertThat(jdbc.queryForObject("SELECT amount_to_collect FROM staff_loan_collection_lines WHERE"
                + " staff_loan_id = ?", BigDecimal.class, reduced.loanId())).isEqualByComparingTo("90.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_redemptions WHERE source = 'GETMORE_SYNC'"
                + " AND redeemed_at < ? AND settlement_cycle IS NULL", Integer.class, cutoff)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE event_type ="
                + " 'STAFF_LOAN_COLLECTION_FINALISED'", Integer.class)).isEqualTo(1);
        portal("POST", statement + "/finalisation", UserGroup.SUPER_ADMIN, null, 409);

        // A refund GetMore made before the cut-off, read only now: late in the next payday, and listed on this one.
        SimRefund late = till.refund(soldOrder, new BigDecimal("5.00"), "AVD-RF-0072");
        jdbc.update("UPDATE getmore_sim_creditmemos SET created_at = ? WHERE increment_id = ?",
                cutoff.minusHours(2), late.creditMemoNumber());
        assertThat(sync.sync(reduced.voucherIdOrZero(), GetMoreSyncTrigger.OPERATOR).refundsRecorded()).isEqualTo(1);

        JsonNode kept = portal("GET", statement, UserGroup.FINANCE, null, 200).path("data");
        assertThat(lineOf(kept, "E1076").path("amountToCollect").decimalValue()).as("as sent to payroll")
                .isEqualByComparingTo("90.00");
        assertThat(lineOf(kept, "E1076").path("spentNet").decimalValue()).as("as known when finalised")
                .isEqualByComparingTo("90.00");
        assertThat(kept.path("lateAdjustments").size()).isEqualTo(1);
        JsonNode adjustment = kept.path("lateAdjustments").get(0);
        assertThat(adjustment.path("loanReference").asString()).isEqualTo(reduced.reference());
        assertThat(adjustment.path("refunds").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(adjustment.path("net").decimalValue()).isEqualByComparingTo("-5.00");
        JsonNode next = portal("GET", "/lending/v1/staff-loan-collections/" + payday.plusMonths(1),
                UserGroup.FINANCE, null, 200).path("data").path("merchantSettlement");
        assertThat(next.path("previousCycle").path("payday").asString()).isEqualTo(payday.toString());
        JsonNode lateLine = null;
        for (JsonNode line : next.path("lines")) {
            if (line.path("voucherId").asLong() == reduced.voucherIdOrZero()) {
                lateLine = line;
            }
        }
        assertThat(lateLine).isNotNull();
        assertThat(lateLine.path("late").asBoolean()).isTrue();
        assertThat(lateLine.path("refunds").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(lateLine.path("net").decimalValue()).isEqualByComparingTo("-5.00");
    }

    /** The statement line of a loan, by its borrower's employee number. */
    private static JsonNode lineOf(JsonNode statement, String employeeNumber) {
        JsonNode lines = statement.has("data") ? statement.path("data").path("lines") : statement.path("lines");
        for (JsonNode line : lines) {
            if (line.path("employeeNumber").asString().equals(employeeNumber)) {
                return line;
            }
        }
        throw new AssertionError("no line for " + employeeNumber + " in " + lines);
    }

    // ---- the borrower -----------------------------------------------------------------------------------------------

    private Member member(String employeeNumber, String msisdn) {
        long id = StaffLoanRows.member(jdbc, employeeNumber, msisdn);
        return new Member(id, employeeNumber, "+" + msisdn);
    }

    private String assertion(Member member) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/lending/v1/auth/test-assertions"))
                .header("X-Api-Key", ASSERTION_KEY).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{ \"phone\": \"" + member.phone()
                        + "\", \"methods\": [\"pin\"] }")));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data").path("assertion").asString();
    }

    private String signIn(Member member) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/lending/v1/auth/exchange"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{ \"assertion\": \"" + assertion(member) + "\" }")));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("data").path("accessToken").asString();
    }

    /** Signs in, applies, quotes USD 150.00 and accepts it. */
    private Accepted borrow(Member member) throws Exception {
        String token = signIn(member);
        HttpResponse<String> applied = send(HttpRequest.newBuilder(uri(JOURNEY + "/offers"))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()));
        assertThat(applied.statusCode()).as(applied.body()).isIn(200, 201);
        long offerId = json.readTree(applied.body()).path("data").path("offer").path("offerId").asLong();
        JsonNode agreement = borrower("POST", JOURNEY + "/quote", token,
                "{ \"offerId\": " + offerId + ", \"amount\": 150.00 }", 200).path("data").path("agreement");
        String body = """
                { "offerId": %d, "amount": 150.00, "agreementVersion": %d, "agreementSha256": "%s",
                  "assertion": "%s" }""".formatted(offerId, agreement.path("version").asInt(),
                agreement.path("contentSha256").asString(), assertion(member));
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri(JOURNEY + "/loans"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("X-Device-Id", "it-device-" + member.employeeNumber())
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode loan = json.readTree(response.body()).path("data");
        String reference = loan.path("reference").asString();
        long loanId = jdbc.queryForObject("SELECT id FROM staff_loans WHERE reference = ?", Long.class, reference);
        Long voucherId = jdbc.queryForList("SELECT id FROM vouchers WHERE staff_loan_id = ?", Long.class, loanId)
                .stream().findFirst().orElse(0L);
        return new Accepted(reference, loanId, voucherId, loan);
    }

    private JsonNode borrower(String method, String path, String token, String body, int status) throws Exception {
        return call(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token), method, body,
                status);
    }

    // ---- the simulator and the portal -------------------------------------------------------------------------------

    private static String sale(String reference, String amount, String tillReference, boolean invoiced) {
        return """
                { "loanReference": "%s", "amount": %s, "currency": "USD", "outletId": "GM-AVD",
                  "outletName": "GetMore Avondale", "reference": "%s", "invoiced": %s }"""
                .formatted(reference, amount, tillReference, invoiced);
    }

    private JsonNode simulator(String method, String path, String body, int status) throws Exception {
        return call(HttpRequest.newBuilder(uri(SIMULATOR + path)).header("X-Api-Key", SIMULATOR_KEY), method, body,
                status);
    }

    private JsonNode portal(String method, String path, UserGroup role, String body, int status) throws Exception {
        String username = "it-flow-" + role.name().toLowerCase();
        User user = userRepository.findByUsername(username).orElseGet(() -> {
            User created = new User();
            created.setUsername(username);
            created.setExternalSystemId(UUID.randomUUID().toString());
            created.setFirstName("IT");
            created.setLastName(role.name());
            created.setGroups(new HashSet<>(Set.of(role)));
            created.setMerchant(merchantRepository.findByMerchantCode("getmore-groceries").orElseThrow());
            created.setTemporaryPassword(false);
            return userRepository.save(created);
        });
        return call(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer "
                + jwtService.generateToken(user)), method, body, status);
    }

    private JsonNode call(HttpRequest.Builder request, String method, String body, int status) throws Exception {
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        HttpResponse<String> response = send(request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)));
        assertThat(response.statusCode()).as("%s -> %s", method, response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    // ---- the rows ---------------------------------------------------------------------------------------------------

    private Map<String, Object> issuance(long loanId) {
        return jdbc.queryForMap("SELECT status, coupon_prefix, generate_calls FROM staff_loan_voucher_issuances"
                + " WHERE staff_loan_id = ?", loanId);
    }

    private int couponsOn(String prefix) {
        return jdbc.queryForObject("SELECT count(*) FROM getmore_sim_coupons WHERE code LIKE ?", Integer.class,
                prefix + "%");
    }
}
