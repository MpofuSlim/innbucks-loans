package zw.co.innbucks.loans.core.disbursements;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import zw.co.innbucks.loans.core.config.HttpClientConfig;
import zw.co.innbucks.loans.core.testsupport.TestOutboundHttp;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the bank's inquiry as the Staff Grocery Loan's payout reads it,
 * {@code GET /bank/api/loan/inquiry/{participantReference}} through {@link InnbucksLoanInquiryClient}.
 *
 * <p>The REQUEST follows the collection (bearer, X-Api-Key, the reference as trace id). The ANSWERS are the repo's
 * existing model of the inquiry, NOT recorded responses (see {@link InnbucksLoanApiContractTest}): what is pinned is
 * how leniently they are read. Only the bank's own envelope decides: 000 in a 2xx is FOUND, 004 is NOT_FOUND. Anything
 * else is UNANSWERED, and nothing ever throws.
 */
class InnbucksLoanInquiryContractTest {

    private static final String API_KEY = "test-api-key";
    private static final String LOGIN = "/auth/third-party";
    private static final String INQUIRY = "/bank/api/loan/inquiry/";
    private static final String TOKEN = "tok-abc";
    private static final String REFERENCE = "SGL-2026-000143";

    private static WireMockServer wireMock;
    private InnbucksLoanInquiry inquiry;

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
        inquiry = inquiryAt(base, base);
    }

    private static InnbucksLoanInquiry inquiryAt(String authBase, String loanBase) {
        InnbucksParameters params = new InnbucksParameters();
        params.setApiKey(API_KEY);
        params.setUsername("test-user");
        params.setPassword("test-pass");
        params.setAuthEndpoint(authBase + LOGIN);
        params.setLoanInquiryEndpoint(loanBase + INQUIRY + "{participantReference}");
        RestTemplate restTemplate = new RestTemplate(TestOutboundHttp.POOL.requestFactory(500, 2000));
        return new InnbucksLoanInquiryClient(restTemplate, params,
                new InnbucksAuthService(restTemplate, params, new HttpClientConfig()));
    }

    private static void answer(int status, String body) {
        wireMock.stubFor(get(urlEqualTo(INQUIRY + REFERENCE)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("000 SETTLED: FOUND, with the bank's status, loan account and echoes; asked as the collection does")
    void settledIsFound() {
        answer(200, """
                {"responseCode":"000","responseDescription":"Approved or completed successfully",
                 "participantReference":"SGL-2026-000143",
                 "additionalData":{"loanDetails":{"status":"SETTLED","participantReference":"SGL-2026-000143",
                   "amount":15000,"interestRate":0.0,"msisdn":"263772123123","loanAccountNumber":"LA-0001234",
                   "idNumber":"63-2345678B42"}}}
                """);

        InquiryAnswer found = inquiry.inquire(REFERENCE);

        assertThat(found.kind()).isEqualTo(InquiryAnswer.Kind.FOUND);
        assertThat(found.httpStatus()).isEqualTo(200);
        assertThat(found.responseCode()).isEqualTo("000");
        assertThat(found.bankStatus()).isEqualTo("SETTLED");
        assertThat(found.bankLoanAccount()).isEqualTo("LA-0001234");
        assertThat(found.echoedReference()).isEqualTo(REFERENCE);
        assertThat(found.amount()).isEqualByComparingTo("15000");
        assertThat(found.interestRate()).isEqualByComparingTo("0");
        assertThat(found.echoedMsisdn()).isEqualTo("263772123123");
        assertThat(found.echoedSettlementAccount()).isNull();
        // The raw answer is kept for the operators, redacted: every reader of staff loans sees it, so no ID number,
        // account number or phone number survives in full.
        assertThat(found.raw()).contains("SETTLED", "****3123")
                .doesNotContain("63-2345678B42", "LA-0001234", "263772123123");
        wireMock.verify(1, getRequestedFor(urlEqualTo(INQUIRY + REFERENCE))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("X-Trace-Id", equalTo(REFERENCE))
                .withHeader("Accept", equalTo("application/json"))
                .withoutHeader("traceparent"));
    }

    @Test
    @DisplayName("000 PROCESSING: FOUND, not yet paid")
    void processingIsFound() {
        answer(200, """
                {"responseCode":"000","additionalData":{"loanDetails":{"status":"PROCESSING"}}}
                """);

        InquiryAnswer found = inquiry.inquire(REFERENCE);

        assertThat(found.kind()).isEqualTo(InquiryAnswer.Kind.FOUND);
        assertThat(found.bankStatus()).isEqualTo("PROCESSING");
        assertThat(found.bankLoanAccount()).isNull();
        assertThat(found.echoedReference()).isNull();
        assertThat(found.amount()).isNull();
    }

    @Test
    @DisplayName("004 in a 2xx is NOT_FOUND")
    void notFoundIn2xx() {
        answer(200, "{\"responseCode\":\"004\",\"responseDescription\":\"Record not found\"}");

        InquiryAnswer answer = inquiry.inquire(REFERENCE);

        assertThat(answer.kind()).isEqualTo(InquiryAnswer.Kind.NOT_FOUND);
        assertThat(answer.responseCode()).isEqualTo("004");
        assertThat(answer.raw()).contains("Record not found");
    }

    @Test
    @DisplayName("004 in a 404's body is NOT_FOUND")
    void notFoundIn404Body() {
        answer(404, "{\"responseCode\":\"004\",\"responseDescription\":\"Record not found\"}");

        InquiryAnswer answer = inquiry.inquire(REFERENCE);

        assertThat(answer.kind()).isEqualTo(InquiryAnswer.Kind.NOT_FOUND);
        assertThat(answer.httpStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a bare 404 (a gateway's, a wrong path's) says nothing about the loan: UNANSWERED")
    void bare404IsUnanswered() {
        wireMock.stubFor(get(urlEqualTo(INQUIRY + REFERENCE)).willReturn(aResponse().withStatus(404)));

        InquiryAnswer answer = inquiry.inquire(REFERENCE);

        assertThat(answer.kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);
        assertThat(answer.httpStatus()).isEqualTo(404);
        assertThat(answer.bankUnreachable()).as("the bank answered, about this loan").isFalse();
    }

    @Test
    @DisplayName("an HTML page with a 200 is not the bank's envelope: UNANSWERED, the page kept")
    void html200IsUnanswered() {
        wireMock.stubFor(get(urlEqualTo(INQUIRY + REFERENCE)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html><body>Maintenance</body></html>")));

        InquiryAnswer answer = inquiry.inquire(REFERENCE);

        assertThat(answer.kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);
        assertThat(answer.raw()).contains("Maintenance");
        assertThat(answer.bankUnreachable()).isFalse();
    }

    @Test
    @DisplayName("a 502 is UNANSWERED; so is 000 in a 5xx body, and any other responseCode")
    void otherAnswersAreUnanswered() {
        answer(502, "{\"message\":\"bad gateway\"}");
        InquiryAnswer badGateway = inquiry.inquire(REFERENCE);
        assertThat(badGateway.kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);
        assertThat(badGateway.bankUnreachable()).isTrue();

        answer(500, "{\"responseCode\":\"000\",\"additionalData\":{\"loanDetails\":{\"status\":\"SETTLED\"}}}");
        assertThat(inquiry.inquire(REFERENCE).kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);

        answer(200, "{\"responseCode\":\"999\",\"responseDescription\":\"System error\"}");
        InquiryAnswer other = inquiry.inquire(REFERENCE);
        assertThat(other.kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);
        assertThat(other.responseCode()).isEqualTo("999");
        assertThat(other.bankUnreachable()).as("an answer about this loan, if one that says nothing").isFalse();
    }

    @Test
    @DisplayName("fields nobody modelled, a non-ISO date and numbers written as strings never fail the answer")
    void readsLeniently() {
        answer(200, """
                {"responseCode":"000","newEnvelopeField":{"nested":[1,2,3]},
                 "disbursementDate":"29/10/2026 14:05","amount":"150.00",
                 "additionalData":{"loanDetails":{"status":"settled","interestRate":"3.5",
                   "created":"29-10-2026","settlementAccount":"1234567890","msisdn":"263772123123",
                   "someFlag":true,"tenureInMonths":"one"}}}
                """);

        InquiryAnswer found = inquiry.inquire(REFERENCE);

        assertThat(found.kind()).isEqualTo(InquiryAnswer.Kind.FOUND);
        assertThat(found.bankStatus()).as("as the bank wrote it").isEqualTo("settled");
        assertThat(found.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(found.interestRate()).isEqualByComparingTo("3.5");
        assertThat(found.echoedSettlementAccount()).isEqualTo("1234567890");
        assertThat(found.echoedMsisdn()).as("kept apart: which names where it paid is the payout's to decide")
                .isEqualTo("263772123123");
    }

    @Test
    @DisplayName("an account payout's answer that echoes only the borrower's phone: the phone, never read as the"
            + " account")
    void anEchoedPhoneIsNeverTheAccount() {
        answer(200, """
                {"responseCode":"000","additionalData":{"loanDetails":{"status":"SETTLED","msisdn":"263772123123"}}}
                """);

        InquiryAnswer found = inquiry.inquire(REFERENCE);

        assertThat(found.echoedMsisdn()).isEqualTo("263772123123");
        assertThat(found.echoedSettlementAccount()).isNull();
    }

    @Test
    @DisplayName("a loan account longer than the column is not kept")
    void anOverlongLoanAccountIsDropped() {
        answer(200, "{\"responseCode\":\"000\",\"additionalData\":{\"loanDetails\":{\"status\":\"SETTLED\","
                + "\"loanAccountNumber\":\"" + "9".repeat(65) + "\"}}}");

        InquiryAnswer found = inquiry.inquire(REFERENCE);

        assertThat(found.kind()).isEqualTo(InquiryAnswer.Kind.FOUND);
        assertThat(found.bankLoanAccount()).isNull();
    }

    @Test
    @DisplayName("a 401 logs in again and asks once more, with the fresh token")
    void a401IsAskedAgainOnce() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("login").whenScenarioStateIs(STARTED)
                .willReturn(okJson("{\"accessToken\":\"tok-old\"}")).willSetStateTo("second"));
        wireMock.stubFor(post(urlEqualTo(LOGIN)).inScenario("login").whenScenarioStateIs("second")
                .willReturn(okJson("{\"accessToken\":\"tok-new\"}")));
        wireMock.stubFor(get(urlEqualTo(INQUIRY + REFERENCE)).withHeader("Authorization", equalTo("Bearer tok-old"))
                .willReturn(aResponse().withStatus(401)));
        wireMock.stubFor(get(urlEqualTo(INQUIRY + REFERENCE)).withHeader("Authorization", equalTo("Bearer tok-new"))
                .willReturn(okJson("{\"responseCode\":\"004\"}")));

        InquiryAnswer answer = inquiry.inquire(REFERENCE);

        assertThat(answer.kind()).isEqualTo(InquiryAnswer.Kind.NOT_FOUND);
        wireMock.verify(1, getRequestedFor(urlEqualTo(INQUIRY + REFERENCE))
                .withHeader("Authorization", equalTo("Bearer tok-old")));
        wireMock.verify(1, getRequestedFor(urlEqualTo(INQUIRY + REFERENCE))
                .withHeader("Authorization", equalTo("Bearer tok-new")));
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("a 401 on the second ask too is UNANSWERED, and says the bank cannot be reached")
    void a401TwiceIsUnreachable() {
        wireMock.stubFor(get(urlEqualTo(INQUIRY + REFERENCE)).willReturn(aResponse().withStatus(401)));

        InquiryAnswer answer = inquiry.inquire(REFERENCE);

        assertThat(answer.kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);
        assertThat(answer.httpStatus()).isEqualTo(401);
        assertThat(answer.bankUnreachable()).isTrue();
        wireMock.verify(2, getRequestedFor(urlEqualTo(INQUIRY + REFERENCE)));
    }

    @Test
    @DisplayName("a login outage is UNANSWERED, and nothing is asked")
    void aLoginOutageIsUnanswered() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(503)));

        InquiryAnswer answer = inquiry.inquire(REFERENCE);

        assertThat(answer.kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);
        assertThat(answer.httpStatus()).isZero();
        assertThat(answer.bankUnreachable()).isTrue();
        assertThat(answer.raw()).startsWith("InnBucks login failed");
        wireMock.verify(0, getRequestedFor(urlPathMatching(INQUIRY + ".*")));
    }

    @Test
    @DisplayName("a read timeout, a reset or a refused connection is UNANSWERED, never thrown")
    void transportFailuresAreUnanswered() {
        wireMock.stubFor(get(urlEqualTo(INQUIRY + REFERENCE)).willReturn(okJson("{}").withFixedDelay(3_000)));
        assertThat(inquiry.inquire(REFERENCE).kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);

        wireMock.stubFor(get(urlEqualTo(INQUIRY + REFERENCE))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(inquiry.inquire(REFERENCE).kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);

        InnbucksLoanInquiry refused = inquiryAt("http://localhost:" + wireMock.port(), "http://localhost:1");
        InquiryAnswer answer = refused.inquire(REFERENCE);
        assertThat(answer.kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);
        assertThat(answer.raw()).startsWith("No answer");
    }

    @Test
    @DisplayName("something that is not a reference is never put in the URL")
    void aNonReferenceIsNotAsked() {
        assertThat(inquiry.inquire("SGL-1/../../admin").kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);
        assertThat(inquiry.inquire(null).kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);
        assertThat(inquiry.inquire(" ").kind()).isEqualTo(InquiryAnswer.Kind.UNANSWERED);
        wireMock.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("an answer never prints its account, amount or raw text")
    void toStringHidesTheAnswer() {
        InquiryAnswer answer = new InquiryAnswer(InquiryAnswer.Kind.FOUND, "000", "SETTLED", "LA-0001234", REFERENCE,
                new BigDecimal("15000"), BigDecimal.ZERO, "263772123123", "1234567890",
                "{\"msisdn\":\"263772123123\"}", 200);

        assertThat(answer.toString()).contains("FOUND", "SETTLED", "000")
                .doesNotContain("LA-0001234", "1234567890", "263772123123", "15000");
    }
}
