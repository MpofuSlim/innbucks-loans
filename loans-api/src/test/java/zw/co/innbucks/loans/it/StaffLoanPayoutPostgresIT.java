package zw.co.innbucks.loans.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.StartupTask;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.commission.CommissionGroupRepository;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplate;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplateRepository;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitChange;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitChangeRepository;
import zw.co.innbucks.loans.core.staff.StaffGradeLimitChangeStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatch;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchRepository;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchSource;
import zw.co.innbucks.loans.core.staff.StaffRegisterBatchStatus;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatusJob;
import zw.co.innbucks.loans.core.staff.offer.StaffOffer;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferOrigin;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferRepository;
import zw.co.innbucks.loans.core.staff.offer.StaffOfferStatus;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPairGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cash Staff Grocery Loan's payout end to end, against real Postgres, real HTTP with real tokens through the real
 * security chain, and the bank stood in for by WireMock: the borrower accepts in the SuperApp's endpoints; the payout
 * is sent once the acceptance commits, on the payout thread; the bank's answer settles it; Finance's endpoints send a
 * refused one again under the same reference, ask the bank, and a SUPER_ADMIN records one never booked. On the way,
 * a payout the bank may hold is never sent again and never cancelled, nobody acts on their own loan, and every
 * endpoint renders the loan's LAZY merchant (open-in-view is off) without a LazyInitializationException.
 *
 * <p>The status job is replaced: its first run would be two minutes after boot, and these tests ask the bank
 * themselves, through the operators' "check now".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "it"})
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
class StaffLoanPayoutPostgresIT {

    private static final String BASE = "/lending/v1";
    private static final String JOURNEY = BASE + "/borrower/staff-grocery-loan";
    private static final String LOGIN = "/auth/third-party";
    private static final String APPLY = "/bank/api/loan/apply/pre-approved";
    private static final String INQUIRY = "/bank/api/loan/inquiry/";
    private static final String TEST_ASSERTIONS_KEY = "it-test-assertions-key-0123456789abcdefgh";
    private static final String MERCHANT_NAME = "GetMore Groceries";
    private static final String AGREEMENT = "STAFF GROCERY LOAN AGREEMENT\n\nI, {{borrowerName}}, employee number"
            + " {{employeeNumber}}, borrow {{currency}} {{amount}} from InnBucks Microfinance Bank at {{interestRate}}%"
            + " interest, paid into my {{payoutAccount}}. I will repay {{currency}} {{totalRepayable}}, collected from"
            + " my salary on {{repaymentDate}}.\n\nAccepted on {{acceptedDate}}.";

    private static final WireMockServer BANK = new WireMockServer(wireMockConfig().dynamicPort());
    private static final String TEST_ASSERTIONS_PRIVATE_KEY;

    static {
        BANK.start();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            TEST_ASSERTIONS_PRIVATE_KEY = Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = ItPostgres.createDatabase("loans_payout_e2e_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ItPostgres::user);
        registry.add("spring.datasource.password", ItPostgres::password);
        registry.add("innbucks.base-url", BANK::baseUrl);
        registry.add("innbucks.api-key", () -> "it-innbucks-api-key");
        registry.add("innbucks.username", () -> "it-innbucks-user");
        registry.add("innbucks.password", () -> "it-innbucks-password");
        registry.add("loans.staff-loans.payout.method", () -> "CASH");
        registry.add("loans.staff-loans.payout.destination", () -> "VEENGU_WALLET");
        registry.add("loans.staff-loans.payout.product", () -> "STAFFGROC");
        // Whatever day the test runs on, the loan's repayment date is near enough to pay.
        registry.add("loans.staff-loans.payout.min-days-before-due", () -> "0");
        registry.add("loans.borrower.test-assertions.enabled", () -> "true");
        registry.add("loans.borrower.test-assertions.api-key", () -> TEST_ASSERTIONS_KEY);
        registry.add("loans.borrower.test-assertions.private-key", () -> TEST_ASSERTIONS_PRIVATE_KEY);
    }

    @AfterAll
    static void stopBank() {
        BANK.stop();
    }

    @LocalServerPort
    int port;

    @Autowired private EntityManagerFactory emf;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CommissionGroupRepository commissionGroupRepository;
    @Autowired private InstrumentTemplateRepository templateRepository;
    @Autowired private StaffRegisterBatchRepository batchRepository;
    @Autowired private StaffMemberRepository memberRepository;
    @Autowired private StaffGradeLimitChangeRepository gradeLimitRepository;
    @Autowired private StaffOfferRepository offerRepository;

    @MockitoBean private StaffLoanPayoutStatusJob statusJob;

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private final JsonMapper json = JsonMapper.builder().build();

    /** A borrower on the register, with an offer to take up. */
    private record Borrower(long memberId, String employeeNumber, String msisdn, String nationalId, long offerId) {
    }

    @BeforeEach
    void cell() {
        BANK.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"it-bank-token\"}")));
        if (templateRepository.findFirstByInstrumentTypeOrderByVersionDesc(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT)
                .isEmpty()) {
            templateRepository.save(InstrumentTemplate.builder()
                    .instrumentType(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT).version(1)
                    .title("Staff Grocery Loan Agreement").body(AGREEMENT).publishedBy("it-admin")
                    .publishedAt(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)).build());
        }
        if (merchantRepository.findByStaffLoanMerchantTrue().isEmpty()) {
            merchantRepository.save(merchant("getmore-groceries", MERCHANT_NAME, true));
        }
        if (gradeLimitRepository.findGradesByStatus(StaffGradeLimitChangeStatus.APPROVED).isEmpty()) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            gradeLimitRepository.save(StaffGradeLimitChange.builder().grade("C4").scoreBand("All")
                    .maximumLimit(new BigDecimal("300.00")).effectiveFrom(LocalDate.now(ZoneOffset.UTC).minusDays(60))
                    .status(StaffGradeLimitChangeStatus.APPROVED).proposedBy("hc1").proposedAt(now.minusDays(61))
                    .decidedBy("hc2").decidedAt(now.minusDays(60)).build());
        }
    }

    // ── The journeys ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("accepted in cash: sent as the acceptance commits, BOOKED; check-now finds it SETTLED: PAID, told")
    void acceptedBookedPaid() throws Exception {
        Borrower chipo = borrower("E1012", "263773456789", "632223334C55");
        stubApply(chipo, aResponse().withStatus(201)
                .withBody("{\"responseCode\":\"000\",\"responseDescription\":\"Loan application received\"}"));

        JsonNode accepted = accept(chipo);
        assertThat(accepted.path("status").asString()).isEqualTo("AWAITING_DISBURSEMENT");
        assertThat(accepted.path("payoutMethod").asString()).isEqualTo("CASH");
        assertThat(accepted.path("payoutAccount").asString()).isEqualTo("****6789");
        assertThat(accepted.path("statusMessage").asString())
                .isEqualTo("Your loan is approved. USD 150.00 is being paid into your InnBucks wallet ending 6789.");
        String reference = accepted.path("reference").asString();
        long id = loanId(reference);

        awaitPayout(id, "BOOKED");
        List<LoggedRequest> applies = applies(chipo);
        assertThat(applies).singleElement().satisfies(apply -> {
            JsonNode body = json.readTree(apply.getBodyAsString());
            assertThat(body.path("participantReference").asString()).isEqualTo(reference);
            assertThat(body.path("amount").asInt()).isEqualTo(15000);
            assertThat(body.path("currency").asString()).isEqualTo("USD");
            assertThat(body.path("product").asString()).isEqualTo("STAFFGROC");
            assertThat(body.path("type").asString()).isEqualTo("PERSONAL");
            assertThat(body.path("tenureInMonths").asInt()).isEqualTo(1);
            assertThat(body.path("idNumber").asString()).isEqualTo("63-2223334C55");
            assertThat(body.has("settlementAccount")).as("a wallet payout names no account").isFalse();
            assertThat(apply.getHeader("X-Trace-Id")).isEqualTo(reference);
            assertThat(apply.getHeader("X-Api-Key")).isEqualTo("it-innbucks-api-key");
            assertThat(apply.getHeader("Authorization")).isEqualTo("Bearer it-bank-token");
            assertThat(apply.containsHeader("traceparent")).as("a partner never gets the trace").isFalse();
        });

        BANK.stubFor(get(urlEqualTo(INQUIRY + reference)).willReturn(okJson("""
                {"responseCode":"000","responseDescription":"Approved or completed successfully",
                 "participantReference":"%s",
                 "additionalData":{"loanDetails":{"status":"SETTLED","participantReference":"%s","amount":15000,
                   "interestRate":0.0,"msisdn":"263773456789","loanAccountNumber":"LN-2027-0245"}}}""".formatted(
                reference, reference))));
        HttpResponse<String> checked = send(finance(), postTo(BASE + "/staff-loans/" + id + "/payout/status-check"));
        assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
        JsonNode paid = data(checked);
        assertThat(paid.path("payoutStatus").asString()).isEqualTo("PAID");
        assertThat(paid.path("status").asString()).isEqualTo("DISBURSED");
        assertThat(paid.path("disbursementReference").asString()).isEqualTo(reference);
        assertThat(paid.path("disbursedAt").isNull()).isFalse();
        assertThat(paid.path("bankLoanAccount").asString()).as("never shown in full").isEqualTo("****0245");
        assertThat(jdbc.queryForObject("SELECT bank_loan_account FROM staff_loans WHERE id = ?", String.class, id))
                .as("kept in full").isEqualTo("LN-2027-0245");
        assertThat(paid.path("payoutBankStatus").asString()).isEqualTo("SETTLED");
        assertThat(paid.path("payoutLastAnswer").asString()).contains("SETTLED", "****6789")
                .as("no account or phone number in full, for every reader of staff loans")
                .doesNotContain("LN-2027-0245", "263773456789");
        assertThat(paid.path("payoutApplyAnswer").asString()).as("the booking's answer, kept apart")
                .contains("Loan application received");
        assertThat(checked.body()).doesNotContain("263773456789");
        assertThat(paid.path("merchantName").asString()).as("the LAZY merchant, read in its transaction")
                .isEqualTo(MERCHANT_NAME);
        assertThat(notices(id)).containsExactly("LOAN_PAID_OUT");

        // Settled: not asked about again, not sent again, and the borrower told once.
        assertThat(send(finance(), postTo(BASE + "/staff-loans/" + id + "/payout/status-check")).statusCode())
                .isEqualTo(409);
        assertThat(send(finance(), postTo(BASE + "/staff-loans/" + id + "/payout")).statusCode()).isEqualTo(409);
        assertThat(notices(id)).containsExactly("LOAN_PAID_OUT");
        assertThat(applies(chipo)).hasSize(1);

        JsonNode tile = data(send(borrowerToken(chipo), getFrom(JOURNEY))).path("loan");
        assertThat(tile.path("status").asString()).isEqualTo("DISBURSED");
        assertThat(tile.path("statusMessage").asString())
                .startsWith("USD 150.00 was paid into your InnBucks wallet ending 6789.");
        assertThat(tile.path("voucher").isNull()).isTrue();

        JsonNode listed = data(send(finance(), getFrom(BASE + "/staff-loans?payoutMethod=CASH&payoutStatus=PAID")));
        List<String> references = new java.util.ArrayList<>();
        listed.path("items").forEach(item -> references.add(item.path("reference").asString()));
        assertThat(references).contains(reference);
        HttpResponse<String> detail = send(finance(), getFrom(BASE + "/staff-loans/" + id));
        assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
        assertThat(data(detail).path("loan").path("merchantName").asString()).isEqualTo(MERCHANT_NAME);
        assertThat(data(detail).path("agreement").path("content").asString())
                .contains("paid into my InnBucks wallet ****6789");
    }

    @Test
    @DisplayName("refused by the bank: kept, its slot held; Pay out sends it again under the same reference")
    void refusedSentAgain() throws Exception {
        Borrower tendai = borrower("E2002", "263772002002", "632002002B22");
        stubApply(tendai, aResponse().withStatus(400)
                .withBody("{\"responseCode\":\"400\",\"responseDescription\":\"Product STAFFGROC is not set up\"}"));

        String reference = accept(tendai).path("reference").asString();
        long id = loanId(reference);
        awaitPayout(id, "REFUSED");
        JsonNode refused = data(send(finance(), getFrom(BASE + "/staff-loans?payoutStatus=REFUSED"))).path("items")
                .get(0);
        assertThat(refused.path("reference").asString()).isEqualTo(reference);
        assertThat(refused.path("status").asString()).isEqualTo("AWAITING_DISBURSEMENT");
        assertThat(refused.path("payoutMessage").asString()).startsWith("The bank refused it (HTTP 400)");
        assertThat(refused.path("payoutLastAnswer").asString()).contains("Product STAFFGROC is not set up");
        assertThat(notices(id)).isEmpty();

        // The borrower, signed in to the portal as Finance, cannot send their own.
        User own = user("it-finance-tendai", UserGroup.FINANCE, null, "0772002002");
        HttpResponse<String> theirs = send(jwtService.generateToken(own), postTo(BASE + "/staff-loans/" + id
                + "/payout"));
        assertThat(theirs.statusCode()).as(theirs.body()).isEqualTo(403);
        assertThat(json.readTree(theirs.body()).path("message").asString())
                .isEqualTo("it-finance-tendai is a party to staff loan " + reference
                        + " and cannot send its payout; someone else must");
        assertThat(applies(tendai)).hasSize(1);
        // Nor may Credit, or Human Capital.
        assertThat(send(jwtService.generateToken(user("it-credit", UserGroup.CREDIT_MANAGER, null, null)),
                postTo(BASE + "/staff-loans/" + id + "/payout")).statusCode()).isEqualTo(403);

        stubApply(tendai, aResponse().withStatus(201).withBody("{}"));
        HttpResponse<String> sent = send(finance(), postTo(BASE + "/staff-loans/" + id + "/payout"));
        assertThat(sent.statusCode()).as(sent.body()).isEqualTo(202);
        assertThat(json.readTree(sent.body()).path("code").asString()).isEqualTo("ACCEPTED");
        assertThat(data(sent).path("reference").asString()).isEqualTo(reference);
        assertThat(data(sent).path("merchantName").asString()).isEqualTo(MERCHANT_NAME);

        awaitPayout(id, "BOOKED");
        assertThat(applies(tendai)).hasSize(2).allSatisfy(apply -> assertThat(json.readTree(apply.getBodyAsString())
                .path("participantReference").asString()).isEqualTo(reference));
    }

    @Test
    @DisplayName("not processed (429): READY again; Pay all ready sends it, leaving out the caller's own")
    void notSentThenPaidByTheSweep() throws Exception {
        Borrower farai = borrower("E4004", "263774004004", "634004004D44");
        stubApply(farai, aResponse().withStatus(429));

        String reference = accept(farai).path("reference").asString();
        long id = loanId(reference);
        awaitPayoutMessage(id, "READY", "Not booked: the bank did not process it (HTTP 429)");

        User own = user("it-finance-farai", UserGroup.FINANCE, "63-4004004-D-44", null);
        HttpResponse<String> skipped = send(jwtService.generateToken(own), postTo(BASE + "/staff-loans/payouts"));
        assertThat(skipped.statusCode()).as(skipped.body()).isEqualTo(202);
        assertThat(data(skipped).path("skippedOwn").asInt()).isEqualTo(1);
        assertThat(applies(farai)).hasSize(1);

        stubApply(farai, aResponse().withStatus(201).withBody("{}"));
        HttpResponse<String> swept = send(finance(), postTo(BASE + "/staff-loans/payouts"));
        assertThat(swept.statusCode()).as(swept.body()).isEqualTo(202);
        assertThat(data(swept).path("ready").asInt()).isEqualTo(1);
        assertThat(data(swept).path("skippedOwn").asInt()).isZero();

        awaitPayout(id, "BOOKED");
        assertThat(applies(farai)).hasSize(2);
    }

    @Test
    @DisplayName("in doubt: never sent again or cancelled; never booked only once the bank's 004 is a day old")
    void inDoubtThenNeverBooked() throws Exception {
        Borrower rudo = borrower("E3003", "263713003003", "633003003C33");
        stubApply(rudo, aResponse().withStatus(500).withBody("{\"error\":\"Internal Server Error\"}"));

        String reference = accept(rudo).path("reference").asString();
        long id = loanId(reference);
        awaitPayout(id, "IN_DOUBT");

        assertThat(send(finance(), postTo(BASE + "/staff-loans/" + id + "/payout")).statusCode()).isEqualTo(409);
        HttpResponse<String> cancel = send(admin(), postJson(BASE + "/staff-loans/" + id + "/cancel",
                "{\"reason\":\"Accepted in error\"}"));
        assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(409);
        assertThat(json.readTree(cancel.body()).path("message").asString())
                .contains("is being paid out (payout IN_DOUBT)");
        String note = "{\"note\":\"InnBucks operations confirmed no loan was booked under the reference\"}";
        HttpResponse<String> unasked = send(admin(), postJson(BASE + "/staff-loans/" + id + "/payout/not-booked",
                note));
        assertThat(unasked.statusCode()).as(unasked.body()).isEqualTo(409);
        assertThat(json.readTree(unasked.body()).path("message").asString())
                .endsWith("the bank's inquiry has never reported it missing; check its status first");

        BANK.stubFor(get(urlEqualTo(INQUIRY + reference)).willReturn(okJson(
                "{\"responseCode\":\"004\",\"responseDescription\":\"No loan found\"}")));
        HttpResponse<String> checked = send(finance(), postTo(BASE + "/staff-loans/" + id + "/payout/status-check"));
        assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
        assertThat(data(checked).path("payoutStatus").asString()).isEqualTo("IN_DOUBT");
        assertThat(data(checked).path("payoutNotFoundAt").asString()).isNotEmpty();
        assertThat(data(checked).path("payoutCheckedAt").asString()).isNotEmpty();
        assertThat(data(checked).path("merchantName").asString()).isEqualTo(MERCHANT_NAME);

        HttpResponse<String> tooSoon = send(admin(), postJson(BASE + "/staff-loans/" + id + "/payout/not-booked",
                note));
        assertThat(tooSoon.statusCode()).as(tooSoon.body()).isEqualTo(409);
        assertThat(json.readTree(tooSoon.body()).path("message").asString()).contains("a booking may still be on its"
                + " way");

        // A day later, the bank not asked since: its 004 is old news.
        jdbc.update("UPDATE staff_loans SET payout_not_found_at = payout_not_found_at - interval '25 hours',"
                + " payout_checked_at = payout_checked_at - interval '25 hours' WHERE id = ?", id);
        HttpResponse<String> stale = send(admin(), postJson(BASE + "/staff-loans/" + id + "/payout/not-booked",
                note));
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(json.readTree(stale.body()).path("message").asString())
                .contains("check its status now, then record it within 6 minutes");
        // Asked again: still nothing under the reference, and still since a day ago.
        HttpResponse<String> again = send(finance(), postTo(BASE + "/staff-loans/" + id + "/payout/status-check"));
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(send(finance(), postJson(BASE + "/staff-loans/" + id + "/payout/not-booked", note))
                .statusCode()).as("SUPER_ADMIN alone").isEqualTo(403);
        User own = user("it-admin-rudo", UserGroup.SUPER_ADMIN, "633003003C33", null);
        HttpResponse<String> theirs = send(jwtService.generateToken(own),
                postJson(BASE + "/staff-loans/" + id + "/payout/not-booked", note));
        assertThat(theirs.statusCode()).as(theirs.body()).isEqualTo(403);
        assertThat(notices(id)).isEmpty();

        HttpResponse<String> notBooked = send(admin(), postJson(BASE + "/staff-loans/" + id + "/payout/not-booked",
                note));
        assertThat(notBooked.statusCode()).as(notBooked.body()).isEqualTo(200);
        JsonNode failed = data(notBooked);
        assertThat(failed.path("payoutStatus").asString()).isEqualTo("FAILED");
        assertThat(failed.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(failed.path("cancelledBy").asString()).isEqualTo("it-admin");
        assertThat(failed.path("payoutFailedAt").asString()).isNotEmpty();
        assertThat(failed.path("merchantName").asString()).isEqualTo(MERCHANT_NAME);
        assertThat(notices(id)).containsExactly("LOAN_NOT_PAID_OUT");
        assertThat(applies(rudo)).as("never sent again").hasSize(1);

        JsonNode tile = data(send(borrowerToken(rudo), getFrom(JOURNEY + "/loans"))).path("items").get(0);
        assertThat(tile.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(tile.path("statusMessage").asString())
                .isEqualTo("This loan was cancelled before it was paid out. Nothing is owed.");
    }

    @Test
    @DisplayName("in doubt, then Finance has the bank's word: Record paid makes it PAID and DISBURSED, the borrower told"
            + " once, audited; a READY payout Credit cancels is WITHDRAWN, the borrower told it was not paid")
    void recordedPaidAndCancelled() throws Exception {
        Borrower nyasha = borrower("E5005", "263775005005", "635005005E55");
        stubApply(nyasha, aResponse().withStatus(504));
        String reference = accept(nyasha).path("reference").asString();
        long id = loanId(reference);
        awaitPayout(id, "IN_DOUBT");

        HttpResponse<String> recorded = send(finance(), postJson(BASE + "/staff-loans/" + id + "/payout/paid",
                "{\"bankReference\":\"IBK-PAY-77120\",\"note\":\"InnBucks operations confirmed it landed\"}"));
        assertThat(recorded.statusCode()).as(recorded.body()).isEqualTo(200);
        JsonNode paid = data(recorded);
        assertThat(paid.path("payoutStatus").asString()).isEqualTo("PAID");
        assertThat(paid.path("status").asString()).isEqualTo("DISBURSED");
        assertThat(paid.path("disbursementReference").asString()).isEqualTo(reference);
        assertThat(paid.path("payoutBankSeenAt").asString()).isNotEmpty();
        assertThat(paid.path("merchantName").asString()).as("the LAZY merchant, read in its transaction")
                .isEqualTo(MERCHANT_NAME);
        assertThat(notices(id)).containsExactly("LOAN_PAID_OUT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE event_type ="
                + " 'STAFF_LOAN_PAYOUT_CONFIRMED_PAID' AND entity_id = ?", Integer.class, String.valueOf(id)))
                .isEqualTo(1);
        assertThat(send(finance(), postJson(BASE + "/staff-loans/" + id + "/payout/paid",
                "{\"bankReference\":\"IBK-PAY-77120\",\"note\":\"again\"}")).statusCode())
                .as("never recorded paid twice").isEqualTo(409);
        assertThat(notices(id)).containsExactly("LOAN_PAID_OUT");

        Borrower kuda = borrower("E6006", "263776006006", "636006006F66");
        stubApply(kuda, aResponse().withStatus(429));
        String unsent = accept(kuda).path("reference").asString();
        long unsentId = loanId(unsent);
        awaitPayoutMessage(unsentId, "READY", "Not booked: the bank did not process it (HTTP 429)");

        HttpResponse<String> cancelled = send(jwtService.generateToken(user("it-credit", UserGroup.CREDIT_MANAGER,
                null, null)), postJson(BASE + "/staff-loans/" + unsentId + "/cancel",
                "{\"reason\":\"Accepted in error\"}"));
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(data(cancelled).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(data(cancelled).path("payoutStatus").asString()).isEqualTo("WITHDRAWN");
        assertThat(data(cancelled).path("merchantName").asString()).isEqualTo(MERCHANT_NAME);
        assertThat(notices(unsentId)).containsExactly("LOAN_NOT_PAID_OUT");
    }

    @Test
    @DisplayName("booked, then held by the bank as REJECTED for a day: a SUPER_ADMIN may record it never paid, although"
            + " the bank took it; the borrower told, and the payout still watched")
    void declinedThenNeverPaid() throws Exception {
        Borrower tsitsi = borrower("E7007", "263777007007", "637007007G77");
        stubApply(tsitsi, aResponse().withStatus(201).withBody("{\"responseCode\":\"000\"}"));
        String reference = accept(tsitsi).path("reference").asString();
        long id = loanId(reference);
        awaitPayout(id, "BOOKED");

        BANK.stubFor(get(urlEqualTo(INQUIRY + reference)).willReturn(okJson("""
                {"responseCode":"000","additionalData":{"loanDetails":{"status":"REJECTED",
                 "participantReference":"%s"}}}""".formatted(reference))));
        HttpResponse<String> checked = send(finance(), postTo(BASE + "/staff-loans/" + id + "/payout/status-check"));
        assertThat(checked.statusCode()).as(checked.body()).isEqualTo(200);
        assertThat(data(checked).path("payoutStatus").asString()).isEqualTo("BOOKED");
        assertThat(data(checked).path("payoutDeclinedAt").asString()).isNotEmpty();
        String note = "{\"note\":\"InnBucks credit desk rejected the booking; nothing was paid\"}";
        HttpResponse<String> tooSoon = send(admin(), postJson(BASE + "/staff-loans/" + id + "/payout/not-booked",
                note));
        assertThat(tooSoon.statusCode()).as(tooSoon.body()).isEqualTo(409);
        assertThat(json.readTree(tooSoon.body()).path("message").asString()).contains("held it as REJECTED only since");

        jdbc.update("UPDATE staff_loans SET payout_declined_at = payout_declined_at - interval '25 hours'"
                + " WHERE id = ?", id);
        HttpResponse<String> notPaid = send(admin(), postJson(BASE + "/staff-loans/" + id + "/payout/not-booked",
                note));
        assertThat(notPaid.statusCode()).as(notPaid.body()).isEqualTo(200);
        assertThat(data(notPaid).path("payoutStatus").asString()).isEqualTo("FAILED");
        assertThat(data(notPaid).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(data(notPaid).path("payoutBankSeenAt").asString()).as("the bank did take it").isNotEmpty();
        assertThat(notices(id)).containsExactly("LOAN_NOT_PAID_OUT");

        // Still watched: the bank still holds it as REJECTED, which is no payment.
        HttpResponse<String> watched = send(admin(), postTo(BASE + "/staff-loans/" + id + "/payout/status-check"));
        assertThat(watched.statusCode()).as(watched.body()).isEqualTo(200);
        assertThat(data(watched).path("payoutPaidAfterCancelAt").isMissingNode()).isTrue();
        assertThat(applies(tsitsi)).as("never sent again").hasSize(1);
    }

    @Test
    @DisplayName("the portal's list of cash loans costs the same at 3 more loans as at 12, each with its own merchant")
    void theListCostsTheSameAtAnySize() throws Exception {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        assertThat(stats.isStatisticsEnabled()).as("hibernate.generate_statistics in the it profile").isTrue();
        seedBooked(1, 3);
        long atThree = listStatements(stats, 3);
        seedBooked(4, 12);
        long atTwelve = listStatements(stats, 12);

        assertThat(atTwelve).as("each loan's merchant is read in one batch, never one query per loan")
                .isEqualTo(atThree);
    }

    @Test
    @DisplayName("a refused write names its constraint, never the row: no phone or payout account in the message logged")
    void aRefusedWriteNeverPrintsTheRow() {
        seedBooked(90, 90);
        long id = jdbc.queryForObject("SELECT id FROM staff_loans WHERE reference = 'SGL-2099-000090'", Long.class);

        // A cash loan the bank may have booked is never cancelled (V36), and the refusal is what gets logged.
        Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "UPDATE staff_loans SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = 'it',"
                        + " cancellation_reason = 'it' WHERE id = ?", id));

        assertThat(refused).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(refused.getMessage()).contains("ck_staff_loans_payout_cancelled")
                .as("logServerErrorDetail is off: Postgres' \"Failing row contains\" detail is never printed")
                .doesNotContain("Failing row", "263775000090");
    }

    /** Statements the portal's list of cash loans costs, with at least {@code seeded} of the seeded loans on it. */
    private long listStatements(Statistics stats, int seeded) throws Exception {
        stats.clear();
        HttpResponse<String> listed = send(finance(), getFrom(BASE + "/staff-loans?payoutMethod=CASH&size=100"));
        long statements = stats.getPrepareStatementCount();
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        List<String> merchants = new java.util.ArrayList<>();
        data(listed).path("items").forEach(item -> merchants.add(item.path("merchantName").asString()));
        assertThat(merchants.stream().filter(name -> name.startsWith("Seeded merchant")).count())
                .isEqualTo(seeded);
        return statements;
    }

    /** Cash loans BOOKED, numbered {@code from} to {@code to}, each of a borrower and merchant of its own. */
    private void seedBooked(int from, int to) {
        for (int n = from; n <= to; n++) {
            String msisdn = String.format("2637750000%02d", n);
            Borrower borrower = borrower(String.format("E9%03d", n), msisdn, String.format("6390%05dZ99", n));
            Merchant merchant = merchantRepository.save(merchant("seeded-" + n, "Seeded merchant " + n, false));
            jdbc.update("""
                    INSERT INTO staff_loans (reference, staff_member_id, offer_id, employee_number, full_name, msisdn,
                        grade, merchant_id, amount, currency, interest_rate, total_repayable, due_date,
                        unredeemed_voucher_treatment, status, accepted_at, payout_method, payout_destination,
                        payout_account, payout_status, payout_bank_seen_at)
                    VALUES (?, ?, ?, ?, 'Seeded borrower', ?, 'C4', ?, 150.00, 'USD', 0, 150.00, CURRENT_DATE + 20,
                        'DEBT_STANDS', 'AWAITING_DISBURSEMENT', now(), 'CASH', 'VEENGU_WALLET', ?, 'BOOKED', now())""",
                    String.format("SGL-2099-%06d", n), borrower.memberId(), borrower.offerId(),
                    borrower.employeeNumber(), msisdn, merchant.getId(), msisdn);
        }
    }

    // ── The borrower's journey ───────────────────────────────────────────────────────────────────────────────

    /** Signs the borrower in, quotes 150.00 from their offer and accepts it with a fresh PIN: the loan's view. */
    private JsonNode accept(Borrower borrower) throws Exception {
        String token = borrowerToken(borrower);
        HttpResponse<String> quoted = send(token, postJson(JOURNEY + "/quote",
                "{\"offerId\":" + borrower.offerId() + ",\"amount\":150.00}"));
        assertThat(quoted.statusCode()).as(quoted.body()).isEqualTo(200);
        JsonNode quote = data(quoted);
        assertThat(quote.path("payoutMethod").asString()).isEqualTo("CASH");
        assertThat(quote.path("merchantName").isNull()).isTrue();
        JsonNode agreement = quote.path("agreement");
        HttpResponse<String> accepted = send(token, postJson(JOURNEY + "/loans", """
                {"offerId":%d,"amount":150.00,"agreementVersion":%d,"agreementSha256":"%s","assertion":"%s"}"""
                .formatted(borrower.offerId(), agreement.path("version").asInt(),
                        agreement.path("contentSha256").asString(), assertion(borrower.msisdn())))
                .header("X-Device-Id", "it-device-" + borrower.employeeNumber()));
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return data(accepted);
    }

    private String borrowerToken(Borrower borrower) throws Exception {
        HttpResponse<String> session = send(null, postJson(BASE + "/auth/exchange",
                "{\"assertion\":\"" + assertion(borrower.msisdn()) + "\"}"));
        assertThat(session.statusCode()).as(session.body()).isEqualTo(200);
        return data(session).path("accessToken").asString();
    }

    private String assertion(String phone) throws Exception {
        HttpResponse<String> signed = send(null, postJson(BASE + "/auth/test-assertions",
                "{\"phone\":\"" + phone + "\",\"methods\":[\"pin\"]}").header("X-Api-Key", TEST_ASSERTIONS_KEY));
        assertThat(signed.statusCode()).as(signed.body()).isEqualTo(200);
        return data(signed).path("assertion").asString();
    }

    // ── The bank ─────────────────────────────────────────────────────────────────────────────────────────────

    /** The bank's answer to this borrower's booking (each is told apart by the employee number it carries). */
    private static void stubApply(Borrower borrower, ResponseDefinitionBuilder answer) {
        MappingBuilder apply = post(urlEqualTo(APPLY))
                .withRequestBody(matchingJsonPath("$.employerNumber", equalTo(borrower.employeeNumber())));
        BANK.stubFor(apply.willReturn(answer));
    }

    private static List<LoggedRequest> applies(Borrower borrower) {
        return BANK.findAll(postRequestedFor(urlEqualTo(APPLY))
                .withRequestBody(matchingJsonPath("$.employerNumber", equalTo(borrower.employeeNumber()))));
    }

    /** Waits for the payout thread to settle the loan's payout as {@code status}. */
    private void awaitPayout(long id, String status) throws InterruptedException {
        awaitPayoutMessage(id, status, null);
    }

    private void awaitPayoutMessage(long id, String status, String message) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        Map<String, Object> row = Map.of();
        while (System.nanoTime() < deadline) {
            row = jdbc.queryForMap("SELECT payout_status, payout_message FROM staff_loans WHERE id = ?", id);
            if (status.equals(row.get("payout_status")) && (message == null
                    || String.valueOf(row.get("payout_message")).startsWith(message))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Staff loan " + id + " never reached payout " + status + ": " + row);
    }

    // ── The register, the portal's users ─────────────────────────────────────────────────────────────────────

    private Borrower borrower(String employeeNumber, String msisdn, String nationalId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        StaffRegisterBatch batch = batchRepository.save(StaffRegisterBatch.builder()
                .source(StaffRegisterBatchSource.MANUAL).status(StaffRegisterBatchStatus.PENDING)
                .submittedBy("it").submittedAt(now).totalRows(1).stagedRows(1).rejectedRows(0).build());
        StaffMember member = memberRepository.save(StaffMember.builder().employeeNumber(employeeNumber)
                .fullName("Staff " + employeeNumber).nationalId(nationalId).msisdn(msisdn).grade("C4")
                .department("Treasury").employmentStatus(StaffEmploymentStatus.ACTIVE)
                .engagementDate(LocalDate.of(2017, 3, 1)).walletAccountNumber(msisdn).statusChangedAt(now)
                .createdBatchId(batch.getId()).createdAt(now).updatedBatchId(batch.getId()).updatedAt(now).build());
        long limit = gradeLimitRepository
                .findFirstByGradeAndStatusAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc("C4",
                        StaffGradeLimitChangeStatus.APPROVED, LocalDate.now(ZoneOffset.UTC))
                .orElseThrow().getId();
        StaffOffer offer = offerRepository.save(StaffOffer.builder().staffMemberId(member.getId())
                .origin(StaffOfferOrigin.APPLY).cycleStart(LocalDate.now(ZoneOffset.UTC)).grade("C4").scoreBand("All")
                .gradeLimitChangeId(limit).amount(new BigDecimal("300.00")).issuedAt(now.minusMinutes(5))
                .expiresAt(now.plusDays(7)).status(StaffOfferStatus.ACTIVE).build());
        return new Borrower(member.getId(), employeeNumber, msisdn, nationalId, offer.getId());
    }

    private Merchant merchant(String code, String name, boolean staffLoans) {
        return Merchant.builder().merchantCode(code).companyName(name)
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).accountNumber("263771000001")
                .commissionGroup(commissionGroupRepository.findByNameIgnoreCase(StartupTask.ZERO_BASED_DEFAULT)
                        .orElseThrow())
                .commissionStructure(CommissionStructure.MERCHANT_DEFINED).staffLoanMerchant(staffLoans).build();
    }

    private User user(String username, UserGroup group, String idNumber, String mobile) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            User user = new User();
            user.setUsername(username);
            user.setExternalSystemId(UUID.randomUUID().toString());
            user.setFirstName("IT");
            user.setLastName(username);
            user.setGroups(new java.util.HashSet<>(Set.of(group)));
            user.setMerchant(merchantRepository.findByMerchantCode("it-portal")
                    .orElseGet(() -> merchantRepository.save(merchant("it-portal", "IT portal", false))));
            user.setIdNumber(idNumber);
            user.setMobileNumber(mobile);
            user.setTemporaryPassword(false);
            return userRepository.save(user);
        });
    }

    private String finance() {
        return jwtService.generateToken(user("it-finance", UserGroup.FINANCE, "63-1111111-A-11", "0771111111"));
    }

    private String admin() {
        return jwtService.generateToken(user("it-admin", UserGroup.SUPER_ADMIN, "63-2222222-B-22", "0772222222"));
    }

    // ── Reading back ─────────────────────────────────────────────────────────────────────────────────────────

    private long loanId(String reference) {
        return jdbc.queryForObject("SELECT id FROM staff_loans WHERE reference = ?", Long.class, reference);
    }

    private List<String> notices(long loanId) {
        return jdbc.queryForList("SELECT template FROM staff_notifications WHERE staff_loan_id = ? ORDER BY id",
                String.class, loanId);
    }

    private JsonNode data(HttpResponse<String> response) {
        return json.readTree(response.body()).path("data");
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────────────────────────────────────

    private HttpRequest.Builder getFrom(String path) {
        return HttpRequest.newBuilder(uri(path)).GET();
    }

    private HttpRequest.Builder postTo(String path) {
        return HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody());
    }

    private HttpRequest.Builder postJson(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(String token, HttpRequest.Builder request) throws Exception {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
