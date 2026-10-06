package zw.co.innbucks.loans.core.disbursements;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;
import zw.co.innbucks.loans.core.DisbursementRequest;
import zw.co.innbucks.loans.core.DisbursementResponse;
import zw.co.innbucks.loans.core.ManualDisbursementResponse;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.DisbursementNotAllowedException;
import zw.co.innbucks.loans.core.ledger.DisbursementLedger;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.DisbursementStatus;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanDisbursement;
import zw.co.innbucks.loans.core.loan.LoanDisbursementRepository;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.testsupport.TestOutboundHttp;
import zw.co.innbucks.loans.core.workflow.AssignmentMode;
import zw.co.innbucks.loans.core.workflow.CheckpointDecisionRepository;
import zw.co.innbucks.loans.core.workflow.CheckpointGate;
import zw.co.innbucks.loans.core.workflow.HoldPoint;
import zw.co.innbucks.loans.core.workflow.StageKind;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;
import zw.co.innbucks.loans.core.workflow.WorkflowStageRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract test for the manual-payout deposit, {@code POST /api/transaction/deposit}: what we
 * SEND (the stable per-loan reference, the destination) and how each answer is CLASSIFIED —
 * because a payout mis-read as failed is a payout made twice.
 *
 * <p><b>What is and is not observed.</b> The request fields are the ones this client has
 * always sent. No deposit response has been recorded from InnBucks: the 2xx bodies use the
 * repo's {@link InnbucksDepositResponse} model and the 4xx "refusal" body is that same
 * envelope — an assumption, not a transcript. Replace them with recorded responses once
 * InnBucks supplies them; until then an unrecognised 4xx body is held in doubt, not refused.
 *
 * <p>Pure JUnit + WireMock, no Spring context.
 */
class InnbucksDepositContractTest {

    private static final String API_KEY = "test-api-key";
    private static final String LOGIN = "/auth/third-party";
    private static final String DEPOSIT = "/api/transaction/deposit";
    private static final String TOKEN = "tok-abc";
    private static final String STABLE_REF = "MD-000000042";

    private static WireMockServer wireMock;
    private final List<LoanDisbursement> attempts = new ArrayList<>();
    private final List<WorkflowStage> checkpoints = new ArrayList<>();
    private InnbucksDisbursementService service;
    private Loan loan;

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

        loan = Loan.builder()
                .loanApprovalStatus(LoanApprovalStatus.APPROVED)
                .internalApprovalStatus(InternalApprovalStatus.APPROVED)
                .loanAccountStatus(LoanAccountStatus.FAILED)
                .disbursementStatus(LoanDisbursementStatus.FAILED)
                .bookingFailureKind(BookingFailureKind.REFUSED)
                .disbursedAmount(new BigDecimal("450.00"))
                .mobileNumber("0772123123")
                .merchant(Merchant.builder().companyName("Innbucks")
                        .disbursementType(DisbursementType.CUSTOMER_MOBILE_WALLET).build())
                .internalApprovalBy("cmanager")
                .bookingClaimedAt(LocalDateTime.of(2026, 10, 5, 8, 0))
                .build();
        loan.setId(42L);

        String base = "http://localhost:" + wireMock.port();
        service = serviceAt(base, base);
    }

    /** Login against {@code authBase}; the deposit against {@code depositBase}. */
    private InnbucksDisbursementService serviceAt(String authBase, String depositBase) {
        InnbucksParameters params = new InnbucksParameters();
        params.setApiKey(API_KEY);
        params.setUsername("test-user");
        params.setPassword("test-pass");
        params.setAuthEndpoint(authBase + LOGIN);
        params.setDepositEndpoint(depositBase + DEPOSIT);

        // The production transport (config/OutboundHttp): pooled httpclient5, no automatic retries.
        RestTemplate restTemplate = new RestTemplate(TestOutboundHttp.POOL.requestFactory(500, 500));

        LoanRepository loans = mock(LoanRepository.class);
        when(loans.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));
        LoanDisbursementRepository attemptRepository = mock(LoanDisbursementRepository.class);
        when(attemptRepository.save(any())).thenAnswer(inv -> {
            LoanDisbursement row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId((long) attempts.size() + 1);
                attempts.add(row);
            }
            return row;
        });
        when(attemptRepository.findByLoanId(anyLong())).thenAnswer(inv -> List.copyOf(attempts));
        when(attemptRepository.findById(anyLong())).thenAnswer(inv -> attempts.stream()
                .filter(a -> a.getId().equals(inv.getArgument(0))).findFirst());

        // The checkpoints before booking, none decided; the caller is a SUPER_ADMIN who did not approve the loan.
        WorkflowStageRepository stages = mock(WorkflowStageRepository.class);
        when(stages.findByKindAndHoldPointAndActiveTrueOrderByDisplayOrderAscCodeAsc(eq(StageKind.CHECKPOINT),
                eq(HoldPoint.BEFORE_BOOKING))).thenAnswer(inv -> List.copyOf(checkpoints));
        CheckpointDecisionRepository decisions = mock(CheckpointDecisionRepository.class);
        when(decisions.findByStageCodeInAndLoanIdIn(anyCollection(), anyCollection())).thenReturn(List.of());
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("finance.admin");

        return new InnbucksDisbursementService(loans, mock(LoanNotificationService.class), attemptRepository,
                mock(DeductionCancellationService.class), restTemplate, params,
                new InnbucksAuthService(restTemplate, params), mock(DisbursementLedger.class),
                new CheckpointGate(stages, loans, decisions), authService, mock(PlatformTransactionManager.class));
    }

    private static DisbursementRequest customerDeposit() {
        return DisbursementRequest.builder()
                .amount(new BigDecimal("450.00"))
                .mobileNumber("0772123123")
                .reference("000000042")
                .transactionReference(STABLE_REF)
                .disbursementType(DisbursementType.CUSTOMER_MOBILE_WALLET)
                .build();
    }

    @Test
    @DisplayName("waiting for payout authorisation: refused before anything reaches InnBucks, login included")
    void waitingForPayoutAuthorisationNeverReachesInnbucks() {
        checkpoints.add(WorkflowStage.builder().code("PAYOUT_AUTHORISATION").kind(StageKind.CHECKPOINT)
                .name("Payout authorisation").displayOrder(35).assignment(AssignmentMode.OPTIONAL).targetHours(4)
                .holdPoint(HoldPoint.BEFORE_BOOKING).active(true).activeSince(LocalDateTime.of(2026, 10, 2, 7, 15))
                .updatedBy("admin").updatedAt(LocalDateTime.of(2026, 10, 2, 7, 15)).build());
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(okJson("{\"responseCode\":0}")));

        assertThatThrownBy(() -> service.disburse(42L))
                .isInstanceOf(DisbursementNotAllowedException.class)
                .hasMessageContaining("is waiting for Payout authorisation");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
        assertThat(attempts).isEmpty();
    }

    @Test
    @DisplayName("the loan's credit approver: refused before anything reaches InnBucks, login included")
    void theCreditApproverNeverReachesInnbucks() {
        loan.setInternalApprovalBy("finance.admin");
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(okJson("{\"responseCode\":0}")));

        assertThatThrownBy(() -> service.disburse(42L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("was approved by finance.admin, who cannot also pay it out");
        wireMock.verify(0, anyRequestedFor(anyUrl()));
        assertThat(attempts).isEmpty();
    }

    @Test
    @DisplayName("a read timeout is sent EXACTLY once, under the stable reference, and blocks the next call")
    void readTimeoutIsSentOnceAndHeldInDoubt() {
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(okJson("{\"responseCode\":0}").withFixedDelay(1500)));

        ManualDisbursementResponse result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(ManualDisbursementResponse.Outcome.IN_DOUBT);
        assertThat(result.getReference()).isEqualTo(STABLE_REF);
        wireMock.verify(1, postRequestedFor(urlEqualTo(DEPOSIT)));
        wireMock.verify(postRequestedFor(urlEqualTo(DEPOSIT))
                .withHeader("X-Trace-Id", equalTo(STABLE_REF))
                .withRequestBody(matchingJsonPath("$.reference", equalTo(STABLE_REF))));
        assertThat(attempts).singleElement().satisfies(row ->
                assertThat(row.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.PENDING));

        assertThatThrownBy(() -> service.disburse(42L)).isInstanceOf(DisbursementNotAllowedException.class);
        wireMock.verify(1, postRequestedFor(urlEqualTo(DEPOSIT)));
    }

    @Test
    @DisplayName("success: pays the customer wallet in cents, stable reference on the wire, loan reference in the narration")
    void successPaysTheCustomerWallet() {
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(okJson(
                "{\"responseCode\":0,\"responseMsg\":\"Approved\",\"authNumber\":\"A123\",\"stan\":\"S9\"}")));

        ManualDisbursementResponse result = service.disburse(42L);

        assertThat(result.getOutcome()).isEqualTo(ManualDisbursementResponse.Outcome.DISBURSED);
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.SUCCESS);
        wireMock.verify(1, postRequestedFor(urlEqualTo(DEPOSIT))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withRequestBody(matchingJsonPath("$.reference", equalTo(STABLE_REF)))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("45000")))
                .withRequestBody(matchingJsonPath("$.narration", equalTo("Ref: 000000042")))
                .withRequestBody(matchingJsonPath("$.destinationMsisdn", equalTo("263772123123")))
                .withRequestBody(notMatching(".*destinationAccount.*"))
                // InnBucks is a partner: it never receives our trace (CLAUDE.md "Tracing"),
                // on the deposit or on the login before it.
                .withoutHeader("traceparent"));
        wireMock.verify(postRequestedFor(urlEqualTo(LOGIN)).withoutHeader("traceparent"));
    }

    @Test
    @DisplayName("a merchant (consumer-finance) loan pays the merchant's account, never the customer's wallet")
    void merchantLoanPaysTheMerchantAccount() {
        loan.getMerchant().setDisbursementType(DisbursementType.MERCHANT_MOBILE_WALLET);
        loan.getMerchant().setAccountNumber("123456789");
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(okJson("{\"responseCode\":0}")));

        service.disburse(42L);

        wireMock.verify(1, postRequestedFor(urlEqualTo(DEPOSIT))
                .withRequestBody(matchingJsonPath("$.destinationAccount", equalTo("123456789")))
                .withRequestBody(notMatching(".*destinationMsisdn.*")));
    }

    @Test
    @DisplayName("a 2xx with a non-zero responseCode is a definite refusal (FAILED)")
    void nonZeroResponseCodeIsRefused() {
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(okJson(
                "{\"responseCode\":51,\"responseMsg\":\"Insufficient funds\"}")));

        DisbursementResponse response = service.disburseFunds(customerDeposit());

        assertThat(response.getStatus()).isEqualTo(DisbursementStatus.FAILED);
        assertThat(response.getMessage()).contains("51").contains("Insufficient funds");
    }

    @Test
    @DisplayName("a 4xx carrying InnBucks' own refusal envelope is FAILED")
    void understoodClientErrorIsRefused() {
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"responseCode\":14,\"responseMsg\":\"Invalid destination\"}")));

        DisbursementResponse response = service.disburseFunds(customerDeposit());

        assertThat(response.getStatus()).isEqualTo(DisbursementStatus.FAILED);
        wireMock.verify(1, postRequestedFor(urlEqualTo(DEPOSIT)));
    }

    @Test
    @DisplayName("a 4xx whose body we cannot read (a proxy/WAF page) proves nothing: UNKNOWN")
    void unreadableClientErrorIsUnknown() {
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "text/html").withBody("<html>Blocked</html>")));

        assertThat(service.disburseFunds(customerDeposit()).getStatus()).isEqualTo(DisbursementStatus.UNKNOWN);
    }

    @Test
    @DisplayName("a 409 is UNKNOWN even with an envelope — it may mean this reference already paid")
    void conflictIsUnknown() {
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"responseCode\":94,\"responseMsg\":\"Duplicate reference\"}")));

        assertThat(service.disburseFunds(customerDeposit()).getStatus()).isEqualTo(DisbursementStatus.UNKNOWN);
    }

    @Test
    @DisplayName("a 5xx is UNKNOWN and NOT retried")
    void serverErrorIsUnknownAndNotRetried() {
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(aResponse().withStatus(500)));

        assertThat(service.disburseFunds(customerDeposit()).getStatus()).isEqualTo(DisbursementStatus.UNKNOWN);
        wireMock.verify(1, postRequestedFor(urlEqualTo(DEPOSIT)));
    }

    @Test
    @DisplayName("a connection reset after the request left is UNKNOWN")
    void connectionResetIsUnknown() {
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(service.disburseFunds(customerDeposit()).getStatus()).isEqualTo(DisbursementStatus.UNKNOWN);
        wireMock.verify(1, postRequestedFor(urlEqualTo(DEPOSIT)));
    }

    @Test
    @DisplayName("a 2xx we cannot parse, or without a responseCode, is UNKNOWN — never FAILED")
    void unreadableSuccessIsUnknown() {
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("not json")));
        assertThat(service.disburseFunds(customerDeposit()).getStatus()).isEqualTo(DisbursementStatus.UNKNOWN);

        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).willReturn(okJson("{\"responseMsg\":\"OK\"}")));
        assertThat(service.disburseFunds(customerDeposit()).getStatus()).isEqualTo(DisbursementStatus.UNKNOWN);
    }

    @Test
    @DisplayName("a 401 re-authenticates and replays ONCE, under the same reference")
    void unauthorizedReplaysOnceWithTheSameReference() {
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).inScenario("expired")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("refreshed"));
        wireMock.stubFor(post(urlEqualTo(DEPOSIT)).inScenario("expired")
                .whenScenarioStateIs("refreshed")
                .willReturn(okJson("{\"responseCode\":0}")));

        assertThat(service.disburseFunds(customerDeposit()).getStatus()).isEqualTo(DisbursementStatus.SUCCESS);
        wireMock.verify(2, postRequestedFor(urlEqualTo(DEPOSIT))
                .withRequestBody(matchingJsonPath("$.reference", equalTo(STABLE_REF))));
    }

    @Test
    @DisplayName("a request with no destination type is never sent — no guessing the customer")
    void noDestinationIsNotSent() {
        DisbursementRequest request = customerDeposit();
        request.setDisbursementType(null);

        assertThat(service.disburseFunds(request).getStatus()).isEqualTo(DisbursementStatus.FAILED);
        wireMock.verify(0, postRequestedFor(urlEqualTo(DEPOSIT)));
    }

    @Test
    @DisplayName("a request without the stable reference is never sent")
    void noStableReferenceIsNotSent() {
        DisbursementRequest request = customerDeposit();
        request.setTransactionReference(null);

        assertThat(service.disburseFunds(request).getStatus()).isEqualTo(DisbursementStatus.FAILED);
        wireMock.verify(0, postRequestedFor(urlEqualTo(DEPOSIT)));
    }

    @Test
    @DisplayName("connect-refused on the deposit is UNKNOWN under the strict rule — nothing but InnBucks' answer is proof")
    void connectRefusedIsUnknown() {
        InnbucksDisbursementService refused = serviceAt("http://localhost:" + wireMock.port(), "http://localhost:1");

        assertThat(refused.disburseFunds(customerDeposit()).getStatus()).isEqualTo(DisbursementStatus.UNKNOWN);
    }
}
