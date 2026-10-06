package zw.co.innbucks.loans.core.ndasenda;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.LoanBatchService;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;

import java.util.List;
import zw.co.innbucks.loans.core.testsupport.TestOutboundHttp;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * The daily commit of Ndasenda's open deduction batch. It used to log a network failure as "No pending
 * batch to commit", so an outage read as a quiet day, and the 404 that does mean nothing was open as
 * an error. Each shape is now logged as what it is, and none throws: the next run commits again.
 */
class NdasendaCommitContractTest {

    private static final String AUTH = "/connect/token";
    private static final String COMMIT = "/api/v1/deductions/requests/commit/DC01";

    private static WireMockServer wireMock;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
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
        logger = (Logger) LoggerFactory.getLogger(NdasendaLoanApprovalServiceImpl.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private static NdasendaLoanApprovalServiceImpl serviceAt(String authBase, String commitBase) {
        NdasendaParameters params = new NdasendaParameters();
        params.setAuthEndpoint(authBase + AUTH);
        params.setCommitDeductionsEndpoint(commitBase + "/api/v1/deductions/requests/commit/{id}");
        params.setGrantType("password");
        params.setUsername("test-user");
        params.setPassword("test-pass");
        params.setDeductionCode("DC01");
        params.setSecurityCode("SEC01");
        RestTemplate restTemplate = new RestTemplate(
                new BufferingClientHttpRequestFactory(TestOutboundHttp.POOL.requestFactory(500, 500)));
        return new NdasendaLoanApprovalServiceImpl(restTemplate, new NdasendaAuthService(restTemplate, params),
                params, mock(LoanRepository.class), mock(LoanBatchService.class), mock(LoanNotificationService.class),
                mock(AuditService.class), mock(DeductionCancellationService.class), new MarketTimeZone("ZW"));
    }

    private List<String> logged(Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("committed: the batch Ndasenda closed is named, once, with the bearer token")
    void committed() {
        // The batch shape the client already maps (NdasendaDeductionBatch), with a status from
        // DeductionBatchStatus; Ndasenda's commit response has not been recorded, so this is the mapped
        // shape, not a transcript.
        wireMock.stubFor(post(urlEqualTo(COMMIT)).willReturn(okJson(
                "{\"id\":\"BATCH-0929\",\"recordsCount\":12,\"status\":\"SENT\"}")));

        serviceAt(base, base).commitDeductionRequestsUntilNow();

        wireMock.verify(1, postRequestedFor(urlEqualTo(COMMIT)).withHeader("Authorization", equalTo("Bearer tok-abc")));
        assertThat(logged(Level.INFO)).anyMatch(m -> m.contains("Committed Ndasenda deduction batch BATCH-0929 (12 record(s)"));
        assertThat(logged(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("404: nothing was open, which is a quiet day, not an error")
    void nothingOpen() {
        wireMock.stubFor(post(urlEqualTo(COMMIT)).willReturn(notFound()));

        serviceAt(base, base).commitDeductionRequestsUntilNow();

        assertThat(logged(Level.INFO)).anyMatch(m -> m.contains("No open deduction batch to commit"));
        assertThat(logged(Level.ERROR)).isEmpty();
        assertThat(logged(Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("Ndasenda unreachable: an ERROR saying the commit failed, never 'no pending batch'")
    void unreachable() {
        assertThatCode(() -> serviceAt(base, "http://localhost:1").commitDeductionRequestsUntilNow())
                .doesNotThrowAnyException();

        assertThat(logged(Level.ERROR)).singleElement().asString().contains("NDASENDA COMMIT FAILED: could not reach Ndasenda");
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("No open deduction batch")
                || e.getFormattedMessage().contains("No pending batch"));
    }

    @Test
    @DisplayName("a 5xx: an ERROR, and the next run commits it")
    void upstreamServerError() {
        wireMock.stubFor(post(urlEqualTo(COMMIT)).willReturn(serverError()));

        serviceAt(base, base).commitDeductionRequestsUntilNow();

        assertThat(logged(Level.ERROR)).singleElement().asString().contains("NDASENDA COMMIT FAILED");
        wireMock.verify(1, postRequestedFor(urlEqualTo(COMMIT)));
    }
}
