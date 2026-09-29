package zw.co.reikan.loans.core.ndasenda;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import zw.co.reikan.loans.core.loan.LoanBatchService;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Contract test for the AMOUNT on the deduction lodged with Ndasenda — the
 * grossed monthly instalment, in cents, on both the record and the batch total.
 * {@code toCents} used {@code intValue()}, which truncated any sub-cent
 * remainder and silently wrapped an amount past {@code Integer.MAX_VALUE}.
 *
 * <p>Pure JUnit + WireMock, no Spring context — so the auth service's
 * {@code @Cacheable} is inert and every call fetches a token afresh. The stubbed
 * batch response carries only the id this service reads; it is not an observed
 * Ndasenda response.
 */
class NdasendaDeductionAmountContractTest {

    private static final String AUTH = "/connect/token";
    private static final String DEDUCTIONS = "/api/v1/deductions/requests";

    private static WireMockServer wireMock;
    private NdasendaLoanApprovalServiceImpl service;

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
        wireMock.stubFor(post(urlEqualTo(DEDUCTIONS)).willReturn(okJson("{\"id\":\"BATCH-1\"}")));

        String base = "http://localhost:" + wireMock.port();
        NdasendaParameters params = new NdasendaParameters();
        params.setAuthEndpoint(base + AUTH);
        params.setDeductionRequestsEndpoint(base + DEDUCTIONS);
        params.setGrantType("password");
        params.setUsername("test-user");
        params.setPassword("test-pass");
        params.setDeductionCode("DC01");
        params.setSecurityCode("SEC01");

        RestTemplate restTemplate = new RestTemplate();
        service = new NdasendaLoanApprovalServiceImpl(restTemplate, new NdasendaAuthServiceImpl(restTemplate, params),
                params, mock(LoanRepository.class), mock(LoanBatchService.class), mock(NotificationService.class));
    }

    private static LoanApprovalRequest deduction(String monthlyInstallment) {
        return LoanApprovalRequest.builder()
                .ecnumber("1234567A")
                .idNumber("631234567A63")
                .reference("000000042")
                .monthlyInstallment(new BigDecimal(monthlyInstallment))
                .tenor(12)
                .build();
    }

    @Test
    @DisplayName("a whole-cent instalment goes out as exactly that many cents")
    void wholeCents() {
        LoanApprovalResponse response = service.requestApproval(deduction("123.64"));

        assertThat(response.getBatchNumber()).isEqualTo("BATCH-1");
        wireMock.verify(postRequestedFor(urlEqualTo(DEDUCTIONS))
                .withHeader("Authorization", equalTo("Bearer tok-abc"))
                .withRequestBody(matchingJsonPath("$.records[0].amount", equalTo("12364")))
                .withRequestBody(matchingJsonPath("$.totalAmount", equalTo("12364"))));
    }

    @Test
    @DisplayName("a sub-cent remainder is rounded to the cent, not truncated away")
    void subCentRemainderRounds() {
        service.requestApproval(deduction("123.645"));

        // 123.645 -> 12364.5 -> 12365; intValue() sent 12364.
        wireMock.verify(postRequestedFor(urlEqualTo(DEDUCTIONS))
                .withRequestBody(matchingJsonPath("$.records[0].amount", equalTo("12365")))
                .withRequestBody(matchingJsonPath("$.totalAmount", equalTo("12365"))));
    }

    @Test
    @DisplayName("an instalment too large for the wire's int is refused before anything is lodged")
    void overflowIsRefusedNotWrapped() {
        // 21,474,836.48 is 2^31 cents; intValue() wrapped it to -2147483648.
        assertThatThrownBy(() -> service.requestApproval(deduction("21474836.48")))
                .isInstanceOf(ArithmeticException.class);

        wireMock.verify(0, postRequestedFor(urlEqualTo(DEDUCTIONS)));
    }
}
