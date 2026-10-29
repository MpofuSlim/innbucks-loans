package zw.co.innbucks.loans.core.disbursements;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.HttpClientConfig;
import zw.co.innbucks.loans.core.ledger.DisbursementLedger;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.LoanDisbursementRepository;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.testsupport.TestOutboundHttp;
import zw.co.innbucks.loans.core.workflow.CheckpointGates;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Contract test: the Staff Grocery Loan's cash payout, booked on InnBucks' pre-approved endpoint
 * ({@code POST /bank/api/loan/apply/pre-approved}) by {@link InnbucksDisbursementService#bookPreApproved}.
 *
 * <p>The REQUEST is pinned field by field: the collection's names, cents, {@code dd-MM-yyyy}, and nothing the register
 * does not hold (no address, date of birth or next of kin is made up). The ANSWERS are shapes, not recordings: no real
 * apply answer has been captured yet (see {@link InnbucksLoanApiContractTest}). Every shape is one this client must
 * survive, and each is pinned to how often the booking was sent: once, or twice only after a 401. What each answer
 * MEANS for the loan is the payout service's to decide; here it is only reported, never thrown.
 */
class InnbucksPreApprovedBookingContractTest {

    private static final String API_KEY = "test-api-key";
    private static final String LOGIN = "/auth/third-party";
    private static final String APPLY = "/bank/api/loan/apply/pre-approved";
    private static final String TOKEN = "tok-abc";
    private static final String REFERENCE = "SGL-2026-000143";

    private static WireMockServer wireMock;
    private InnbucksDisbursementService service;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void setup() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"" + TOKEN + "\"}")));
        String base = "http://localhost:" + wireMock.port();
        service = serviceAt(base, base);
    }

    /** Login against {@code authBase}; apply against {@code loanBase}. The production transport, 2 s to read. */
    private static InnbucksDisbursementService serviceAt(String authBase, String loanBase) {
        InnbucksParameters params = new InnbucksParameters();
        params.setApiKey(API_KEY);
        params.setUsername("test-user");
        params.setPassword("test-pass");
        params.setAuthEndpoint(authBase + LOGIN);
        params.setCreateLoanAccountEndpoint(loanBase + APPLY);
        params.setLoanInquiryEndpoint(loanBase + "/bank/api/loan/inquiry/{participantReference}");
        params.setLoanProduct("NANOUS");
        RestTemplate restTemplate = new RestTemplate(TestOutboundHttp.POOL.requestFactory(500, 2000));
        return new InnbucksDisbursementService(mock(LoanRepository.class), mock(LoanNotificationService.class),
                mock(LoanDisbursementRepository.class), mock(DeductionCancellationService.class),
                restTemplate, params, new InnbucksAuthService(restTemplate, params, new HttpClientConfig()),
                mock(DisbursementLedger.class), CheckpointGates.none(), mock(AuthService.class),
                mock(PlatformTransactionManager.class));
    }

    /** A staff borrower paid into their wallet, as the register holds them. */
    private static PreApprovedLoanBooking wallet() {
        return new PreApprovedLoanBooking(REFERENCE, "Chipo", "Banda", "63-2345678B42", new BigDecimal("150.00"),
                "USD", "STAFFGROC", "PERSONAL", 1, "263772123123", null, "InnBucks MicroBank", "E1012",
                LocalDate.of(2015, 2, 2));
    }

    /** The same borrower paid into their bank account. */
    private static PreApprovedLoanBooking account() {
        return new PreApprovedLoanBooking(REFERENCE, "Chipo", "Banda", "63-2345678B42", new BigDecimal("150.00"),
                "USD", "STAFFGROC", "CONSUMER_FINANCE", 1, "263772123123", "1234567890", "InnBucks MicroBank",
                "E1012", LocalDate.of(2015, 2, 2));
    }

    @Test
    @DisplayName("wallet: every field the booking carries, in the collection's names, cents and date format")
    void walletBodyIsPinned() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{}")));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.wasAnswered()).isTrue();
        wireMock.verify(1, postRequestedFor(urlEqualTo(APPLY))
                .withRequestBody(matchingJsonPath("$.participantReference", equalTo(REFERENCE)))
                .withRequestBody(matchingJsonPath("$.firstName", equalTo("Chipo")))
                .withRequestBody(matchingJsonPath("$.lastName", equalTo("Banda")))
                .withRequestBody(matchingJsonPath("$.idNumber", equalTo("63-2345678B42")))
                // USD 150.00 -> 15000 cents.
                .withRequestBody(matchingJsonPath("$.amount", equalTo("15000")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("USD")))
                .withRequestBody(matchingJsonPath("$.product", equalTo("STAFFGROC")))
                .withRequestBody(matchingJsonPath("$.tenureInMonths", equalTo("1")))
                .withRequestBody(matchingJsonPath("$.repaymentFrequency", equalTo("MONTHLY")))
                .withRequestBody(matchingJsonPath("$.type", equalTo("PERSONAL")))
                .withRequestBody(matchingJsonPath("$.msisdn", equalTo("263772123123")))
                .withRequestBody(matchingJsonPath("$.employer", equalTo("InnBucks MicroBank")))
                .withRequestBody(matchingJsonPath("$.employerNumber", equalTo("E1012")))
                .withRequestBody(matchingJsonPath("$.employmentStartDate", equalTo("02-02-2015")))
                // A wallet payout names no account, and nothing the register does not hold is sent.
                .withRequestBody(notContaining("settlementAccount"))
                .withRequestBody(notContaining("address"))
                .withRequestBody(notContaining("dateOfBirth"))
                .withRequestBody(notContaining("maritalStatus"))
                .withRequestBody(notContaining("numberOfDependents"))
                .withRequestBody(notContaining("grossSalary"))
                .withRequestBody(notContaining("placeOfBirth"))
                .withRequestBody(notContaining("nextOfKin"))
                .withRequestBody(notContaining("businessLine"))
                .withRequestBody(notContaining("loanPurpose")));
    }

    @Test
    @DisplayName("headers: the reference is the trace id; bearer and X-Api-Key; never our trace context")
    void headersArePinned() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{}")));

        service.bookPreApproved(wallet());

        wireMock.verify(postRequestedFor(urlEqualTo(APPLY))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("X-Trace-Id", equalTo(REFERENCE))
                .withoutHeader("traceparent"));
    }

    @Test
    @DisplayName("account: the booking names the account it pays as settlementAccount, with the type set")
    void accountBodyCarriesTheSettlementAccount() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{}")));

        service.bookPreApproved(account());

        wireMock.verify(postRequestedFor(urlEqualTo(APPLY))
                .withRequestBody(matchingJsonPath("$.settlementAccount", equalTo("1234567890")))
                .withRequestBody(matchingJsonPath("$.type", equalTo("CONSUMER_FINANCE")))
                .withRequestBody(matchingJsonPath("$.msisdn", equalTo("263772123123"))));
    }

    @Test
    @DisplayName("a 201 with no body is an answer: status kept, no answer text")
    void createdWithoutABody() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(aResponse().withStatus(201)));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.answer()).isNull();
        assertThat(result.responseCode()).as("no envelope").isNull();
        assertThat(result.failure()).isNull();
    }

    @Test
    @DisplayName("a 2xx's envelope is read: 000 (as text or a number) says it went through; any other code does not")
    void theEnvelopeIsRead() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{\"responseCode\":\"000\"}")));
        PreApprovedBookingResult approved = service.bookPreApproved(wallet());
        assertThat(approved.responseCode()).isEqualTo("000");
        assertThat(approved.approved()).isTrue();

        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{\"responseCode\":0,\"responseMsg\":\"OK\"}")));
        assertThat(service.bookPreApproved(wallet()).approved()).as("a number").isTrue();

        // The bank has answered a refusal inside a 2xx on this host before (its deposit API: 200, responseCode 51).
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson(
                "{\"responseCode\":\"05\",\"responseDescription\":\"Customer not found\"}")));
        PreApprovedBookingResult refusedInside = service.bookPreApproved(wallet());
        assertThat(refusedInside.httpStatus()).isEqualTo(200);
        assertThat(refusedInside.responseCode()).isEqualTo("05");
        assertThat(refusedInside.approved()).isFalse();

        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html>OK</html>")));
        assertThat(service.bookPreApproved(wallet()).responseCode()).as("not an envelope").isNull();
    }

    @Test
    @DisplayName("the answer kept names no phone in full: a phone field, or a Zimbabwean mobile anywhere")
    void theAnswerKeptMasksPhones() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("""
                {"responseCode":"000","msisdn":"263772123123","mobileNumber":263782606983,
                 "message":"Paid to 0773456789"}
                """)));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.answer()).contains("****3123", "****6983", "****6789")
                .doesNotContain("263772123123", "263782606983", "0773456789");
    }

    @Test
    @DisplayName("a 2xx answer is kept, redacted: no ID number or account number survives")
    void theAnswerIsKeptRedacted() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("""
                {"responseCode":"000","participantReference":"SGL-2026-000143","idNumber":"63-2345678B42",
                 "settlementAccount":"1234567890","loanAccountNumber":"LA-99887766","status":"PROCESSING"}
                """)));

        PreApprovedBookingResult result = service.bookPreApproved(account());

        assertThat(result.answer()).contains("\"responseCode\":\"000\"", "PROCESSING", REFERENCE)
                .doesNotContain("63-2345678B42", "1234567890", "LA-99887766");
    }

    @Test
    @DisplayName("an answer kept names no account in full, whatever the bank calls it; an account's type stays")
    void theAnswerKeptMasksAnyAccountField() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("""
                {"responseCode":"000","account":"1234567890","walletAccountNo":99887766,
                 "destinationAccount":"ZW-55443322","accountType":"SAVINGS"}
                """)));

        PreApprovedBookingResult result = service.bookPreApproved(account());

        assertThat(result.answer()).contains("\"account\":\"****7890\"", "\"walletAccountNo\":\"****7766\"",
                        "\"destinationAccount\":\"****3322\"", "\"accountType\":\"SAVINGS\"")
                .doesNotContain("1234567890", "99887766", "55443322");
    }

    @Test
    @DisplayName("an answer longer than the column is cut to it")
    void aLongAnswerIsCut() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "text/html")
                .withBody("<html>" + "Service unavailable. ".repeat(300) + "</html>")));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.httpStatus()).isEqualTo(500);
        assertThat(result.answer()).hasSize(BankAnswers.MAX_CHARS).startsWith("<html>Service unavailable.");
    }

    @Test
    @DisplayName("a 401 logs in again and replays the SAME body once; the replay's answer is the answer")
    void a401IsReplayedOnce() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).inScenario("expired").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401)).willSetStateTo("refreshed"));
        wireMock.stubFor(post(urlEqualTo(APPLY)).inScenario("expired").whenScenarioStateIs("refreshed")
                .willReturn(okJson("{}")));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.httpStatus()).isEqualTo(200);
        wireMock.verify(2, postRequestedFor(urlEqualTo(APPLY))
                .withRequestBody(matchingJsonPath("$.participantReference", equalTo(REFERENCE)))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("15000"))));
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("a 401 on the replay too is reported as the 401 it is: two applies, never a third")
    void a401OnTheReplayIsReported() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(aResponse().withStatus(401)));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.httpStatus()).isEqualTo(401);
        assertThat(result.failure()).isNull();
        wireMock.verify(2, postRequestedFor(urlEqualTo(APPLY)));
    }

    @ParameterizedTest(name = "HTTP {0} is reported, and the booking was sent once")
    @ValueSource(ints = {400, 403, 404, 405, 408, 409, 422, 429, 500, 502, 503, 504})
    void everyOtherStatusIsReportedAndSentOnce(int status) {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"responseCode\":\"099\",\"idNumber\":\"63-2345678B42\"}")));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.httpStatus()).isEqualTo(status);
        assertThat(result.wasAnswered()).isTrue();
        assertThat(result.answer()).contains("099").doesNotContain("63-2345678B42");
        wireMock.verify(1, postRequestedFor(urlEqualTo(APPLY)));
        wireMock.verify(1, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("a 302 is reported, not followed: a POST is never re-sent to wherever Location points")
    void aRedirectIsNotFollowed() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(aResponse().withStatus(302)
                .withHeader("Location", "/elsewhere")));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.httpStatus()).isEqualTo(302);
        wireMock.verify(1, postRequestedFor(urlEqualTo(APPLY)));
        wireMock.verify(0, anyRequestedFor(urlEqualTo("/elsewhere")));
    }

    @Test
    @DisplayName("a read timeout may have booked it: a failure that is NOT 'not sent', sent once")
    void aReadTimeoutIsPossiblySent() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{}").withFixedDelay(3_000)));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.wasAnswered()).isFalse();
        assertThat(result.notSent()).isFalse();
        assertThat(result.failure()).isInstanceOf(ResourceAccessException.class);
        wireMock.verify(1, postRequestedFor(urlEqualTo(APPLY)));
    }

    @Test
    @DisplayName("a connection reset mid-answer may have booked it: NOT 'not sent'")
    void aResetIsPossiblySent() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.notSent()).isFalse();
        assertThat(result.failure()).isNotNull();
        wireMock.verify(1, postRequestedFor(urlEqualTo(APPLY)));
    }

    @Test
    @DisplayName("connection refused is 'not sent': the booking never reached InnBucks")
    void connectRefusedIsNotSent() {
        InnbucksDisbursementService refused = serviceAt("http://localhost:" + wireMock.port(), "http://localhost:1");

        PreApprovedBookingResult result = refused.bookPreApproved(wallet());

        assertThat(result.notSent()).isTrue();
        assertThat(result.notBuilt()).as("the bank cannot be reached: every booking behind it would fail too").isFalse();
        assertThat(result.failure()).hasMessageContaining("could not connect to InnBucks");
    }

    @Test
    @DisplayName("a login outage (503) is 'not sent': no booking request is made")
    void aLoginOutageIsNotSent() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(503)
                .withBody("{\"password\":\"echoed-secret\"}")));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.notSent()).isTrue();
        assertThat(result.notBuilt()).isFalse();
        assertThat(result.failure()).hasMessageContaining("InnBucks login failed")
                .hasMessageNotContaining("echoed-secret");
        wireMock.verify(0, postRequestedFor(urlEqualTo(APPLY)));
    }

    @Test
    @DisplayName("a login on the replay that fails is 'not sent': the first attempt's 401 was not processed")
    void aFailedReplayLoginIsNotSent() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("login").whenScenarioStateIs(STARTED)
                .willReturn(okJson("{\"accessToken\":\"" + TOKEN + "\"}")).willSetStateTo("revoked"));
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("login").whenScenarioStateIs("revoked")
                .willReturn(aResponse().withStatus(403)));
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(aResponse().withStatus(401)));

        PreApprovedBookingResult result = service.bookPreApproved(wallet());

        assertThat(result.notSent()).isTrue();
        wireMock.verify(1, postRequestedFor(urlEqualTo(APPLY)));
    }

    @Test
    @DisplayName("a booking that cannot be built (an amount past the wire's int) is 'not sent', and nothing is sent")
    void anUnbuildableBookingIsNotSent() {
        PreApprovedLoanBooking tooLarge = new PreApprovedLoanBooking(REFERENCE, "Chipo", "Banda", null,
                new BigDecimal("21474836.48"), "USD", "STAFFGROC", "PERSONAL", 1, "263772123123", null, null, null,
                null);

        PreApprovedBookingResult result = service.bookPreApproved(tooLarge);

        assertThat(result.notSent()).isTrue();
        assertThat(result.notBuilt()).as("this booking's own reason, not the bank's").isTrue();
        assertThat(result.failure()).hasMessageContaining("could not be built");
        wireMock.verify(0, postRequestedFor(urlEqualTo(APPLY)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("blank optional fields are left out, never sent empty")
    void blankFieldsAreLeftOut() {
        wireMock.stubFor(post(urlEqualTo(APPLY)).willReturn(okJson("{}")));
        PreApprovedLoanBooking bare = new PreApprovedLoanBooking(REFERENCE, null, "Banda", " ", new BigDecimal("10"),
                "USD", "STAFFGROC", "PERSONAL", 1, "263772123123", "  ", "", null, null);

        service.bookPreApproved(bare);

        wireMock.verify(postRequestedFor(urlEqualTo(APPLY))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("1000")))
                .withRequestBody(notContaining("firstName"))
                .withRequestBody(notContaining("idNumber"))
                .withRequestBody(notContaining("settlementAccount"))
                .withRequestBody(notContaining("employer"))
                .withRequestBody(notContaining("employmentStartDate")));
    }
}
