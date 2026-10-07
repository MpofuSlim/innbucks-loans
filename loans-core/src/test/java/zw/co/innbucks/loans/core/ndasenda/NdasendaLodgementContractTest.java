package zw.co.innbucks.loans.core.ndasenda;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.LoanBatchService;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import zw.co.innbucks.loans.core.testsupport.TestOutboundHttp;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

/**
 * Contract test for HOW a lodgement fails, over a real socket: which failures prove the deduction
 * never reached Ndasenda (and may be sent again), and which may follow one that landed (held, never
 * resent). The classification leans on the exact exceptions the production client stack (the pooled
 * httpclient5 factory, config/OutboundHttp) raises, which a mocked RestTemplate cannot show.
 *
 * <p>Pure JUnit + WireMock, no Spring context, so the token cache is inert and every lodgement
 * fetches a token. The stubbed bodies carry only what this service reads; they are not observed
 * Ndasenda responses.</p>
 */
class NdasendaLodgementContractTest {

    private static final String AUTH = "/connect/token";
    private static final String DEDUCTIONS = "/api/v1/deductions/requests";
    private static final String NATIONAL_ID = "631234567A63";

    private static WireMockServer wireMock;
    private String base;

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
        wireMock.stubFor(post(urlEqualTo(AUTH)).willReturn(okJson("{\"access_token\":\"tok-abc\"}")));
        base = "http://localhost:" + wireMock.port();
    }

    /** The production client shape (RestConfig), with timeouts short enough for a test. */
    private static NdasendaLoanApprovalServiceImpl serviceAt(String authBase, String lodgementBase) {
        return serviceAt(authBase, lodgementBase, new MarketTimeZone("ZW"));
    }

    private static NdasendaLoanApprovalServiceImpl serviceAt(String authBase, String lodgementBase,
                                                             MarketTimeZone market) {
        NdasendaParameters params = new NdasendaParameters();
        params.setAuthEndpoint(authBase + AUTH);
        params.setDeductionRequestsEndpoint(lodgementBase + DEDUCTIONS);
        params.setGrantType("password");
        params.setUsername("test-user");
        params.setPassword("test-pass");
        params.setDeductionCode("DC01");
        params.setSecurityCode("SEC01");

        RestTemplate restTemplate = new RestTemplate(TestOutboundHttp.POOL.requestFactory(500, 500));
        return new NdasendaLoanApprovalServiceImpl(restTemplate, new NdasendaAuthService(restTemplate, params),
                params, mock(LoanRepository.class), mock(LoanBatchService.class), mock(LoanNotificationService.class),
                mock(AuditService.class), mock(DeductionCancellationService.class), market);
    }

    private NdasendaLoanApprovalServiceImpl service() {
        return serviceAt(base, base);
    }

    private static LoanApprovalRequest deduction() {
        return LoanApprovalRequest.builder()
                .ecnumber("1234567A")
                .idNumber(NATIONAL_ID)
                .reference("000000042")
                .monthlyInstallment(new BigDecimal("98.50"))
                .tenor(6)
                .build();
    }

    private LodgementException lodgementFails() {
        LodgementException ex = catchThrowableOfType(LodgementException.class, () -> service().requestApproval(deduction()));
        assertThat(ex).as("the lodgement should have failed").isNotNull();
        assertThat(ex.getMessage()).doesNotContain(NATIONAL_ID);
        return ex;
    }

    private static void assertPosts(int count) {
        wireMock.verify(count, postRequestedFor(urlEqualTo(DEDUCTIONS)));
    }

    @Test
    @DisplayName("accepted: the batch id comes back and the deduction was POSTed once")
    void accepted() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(okJson("{\"id\":\"BATCH-1\"}")));

        LoanApprovalResponse response = service().requestApproval(deduction());

        assertThat(response.getBatchNumber()).isEqualTo("BATCH-1");
        assertPosts(1);
        wireMock.verify(postRequestedFor(urlEqualTo(DEDUCTIONS))
                .withHeader("Authorization", equalTo("Bearer tok-abc"))
                .withRequestBody(matchingJsonPath("$.records[0].reference", equalTo("000000042"))));
    }

    @Test
    @DisplayName("the first deduction month is the market's: 00:30 on 1 October in Harare starts in November")
    void deductionMonthIsTheMarkets() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(okJson("{\"id\":\"BATCH-1\"}")));

        // 22:30 UTC on 30 September: already 1 October in Harare (+2) and Nairobi (+3). Read in UTC, the
        // deduction started in October, the month the loan was taken out in.
        Instant justAfterMidnightInHarare = Instant.parse("2026-09-30T22:30:00Z");
        serviceAt(base, base, new MarketTimeZone("ZW", Clock.fixed(justAfterMidnightInHarare, ZoneOffset.UTC)))
                .requestApproval(deduction());
        wireMock.verify(postRequestedFor(urlEqualTo(DEDUCTIONS))
                .withRequestBody(matchingJsonPath("$.records[0].startDate", equalTo("20261101")))
                .withRequestBody(matchingJsonPath("$.records[0].endDate", equalTo("20270430"))));

        // An hour earlier it is still 30 September in Harare: October, as before.
        wireMock.resetRequests();
        serviceAt(base, base, new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-09-30T21:30:00Z"), ZoneOffset.UTC)))
                .requestApproval(deduction());
        wireMock.verify(postRequestedFor(urlEqualTo(DEDUCTIONS))
                .withRequestBody(matchingJsonPath("$.records[0].startDate", equalTo("20261001"))));

        // A Kenyan cell at that same instant is already past midnight (+3).
        wireMock.resetRequests();
        serviceAt(base, base, new MarketTimeZone("KE", Clock.fixed(Instant.parse("2026-09-30T21:30:00Z"), ZoneOffset.UTC)))
                .requestApproval(deduction());
        wireMock.verify(postRequestedFor(urlEqualTo(DEDUCTIONS))
                .withRequestBody(matchingJsonPath("$.records[0].startDate", equalTo("20261101"))));
    }

    @Test
    @DisplayName("connection refused: never sent, Ndasenda unavailable")
    void connectionRefused() {
        LodgementException ex = catchThrowableOfType(LodgementException.class,
                () -> serviceAt(base, "http://localhost:1").requestApproval(deduction()));

        assertThat(ex.getKind()).isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertThat(ex.mayRetry()).isTrue();
    }

    @Test
    @DisplayName("the token endpoint fails: the lodgement is never sent")
    void tokenEndpointFails() {
        wireMock.stubFor(post(urlEqualTo(AUTH)).willReturn(serverError()));
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(okJson("{\"id\":\"BATCH-1\"}")));

        assertThat(lodgementFails().getKind()).isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertPosts(0);
    }

    @Test
    @DisplayName("read timeout: may have landed, so unknown, and sent once")
    void readTimeout() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(okJson("{\"id\":\"BATCH-1\"}").withFixedDelay(2000)));

        LodgementException ex = lodgementFails();

        assertThat(ex.getKind()).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
        assertThat(ex.getMessage()).contains("Read timed out");
        assertPosts(1);
    }

    @Test
    @DisplayName("connection reset after the request: unknown, sent once")
    void resetAfterRequest() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(lodgementFails().getKind()).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
        assertPosts(1);
    }

    @Test
    @DisplayName("5xx: unknown, sent once, and the upstream body is not copied into the reason")
    void serverError5xx() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json").withBody("{\"echo\":\"" + NATIONAL_ID + "\"}")));

        LodgementException ex = lodgementFails();

        assertThat(ex.getKind()).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
        assertThat(ex.getMessage()).isEqualTo("Ndasenda answered HTTP 503");
        assertPosts(1);
    }

    @Test
    @DisplayName("an unreadable 2xx: unknown and sent ONCE - it used to be re-POSTed after a token refresh")
    void unreadable2xxIsNotResent() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html>sign in</html>")));

        LodgementException ex = lodgementFails();

        assertThat(ex.getKind()).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
        assertPosts(1);
        // The token is still refreshed (first fetch + refresh), so the next lodgement starts clean.
        wireMock.verify(2, postRequestedFor(urlEqualTo(AUTH)));
    }

    @Test
    @DisplayName("a 2xx with no batch id: unknown, sent once")
    void accepted2xxWithoutBatchId() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(okJson("{}")));

        assertThat(lodgementFails().getKind()).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
        assertPosts(1);
    }

    @Test
    @DisplayName("400: Ndasenda's own refusal, nothing lodged")
    void badRequestIsRefused() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(badRequest()
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"" + NATIONAL_ID + "\"}")));

        LodgementException ex = lodgementFails();

        assertThat(ex.getKind()).isEqualTo(LodgementException.Kind.REFUSED);
        assertThat(ex.mayRetry()).isFalse();
        assertPosts(1);
    }

    @Test
    @DisplayName("409: may mean the reference is already lodged, so unknown - never a refusal")
    void conflictIsUnknown() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(aResponse().withStatus(409)));

        assertThat(lodgementFails().getKind()).isEqualTo(LodgementException.Kind.OUTCOME_UNKNOWN);
        assertPosts(1);
    }

    @Test
    @DisplayName("401 then 200: the one permitted replay, after a token refresh")
    void unauthorizedThenAccepted() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).inScenario("token").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(unauthorized()).willSetStateTo("refreshed"));
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).inScenario("token").whenScenarioStateIs("refreshed")
                .willReturn(okJson("{\"id\":\"BATCH-2\"}")));

        assertThat(service().requestApproval(deduction()).getBatchNumber()).isEqualTo("BATCH-2");
        assertPosts(2);
    }

    @Test
    @DisplayName("401 twice: nothing processed, Ndasenda unavailable, and no third POST")
    void unauthorizedTwice() {
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(unauthorized()));

        assertThat(lodgementFails().getKind()).isEqualTo(LodgementException.Kind.NDASENDA_UNAVAILABLE);
        assertPosts(2);
    }
}
