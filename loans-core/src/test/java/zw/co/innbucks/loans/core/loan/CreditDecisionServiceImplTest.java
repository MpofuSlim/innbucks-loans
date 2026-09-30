package zw.co.innbucks.loans.core.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.document.DocumentOrigin;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.document.LoanDocumentRepository;
import zw.co.innbucks.loans.core.document.LoanDocumentSummary;
import zw.co.innbucks.loans.core.exception.BusinessException;
import zw.co.innbucks.loans.core.exception.LoanApprovalException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.user.User;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Credit's decision (FR-SSB-015): a reason code and a comment on every decision (FR-PBL-027), nobody
 * approving a loan they originated, resubmitted, amended a document of or are a party to (FR-PBL-029,
 * FR-SSB-009), no approval while a payslip review is pending (FR-SSB-007), and every action kept in
 * an append-only log with the loan data it was based on (FR-PBL-032). The refusals are TYPED so they reach
 * the client as a 400/403/404 with their message.
 */
class CreditDecisionServiceImplTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The seeded codes the tests use, active and not. */
    private static final Map<String, CreditReasonCode> REASON_CODES = Stream.of(
                    code("APPROVE_WITHIN_POLICY", InternalApprovalStatus.APPROVED, "Meets credit policy", true),
                    code("LEGACY_APPROVAL", InternalApprovalStatus.APPROVED, "Approved before reason codes were recorded", false),
                    code("REJECT_OTHER", InternalApprovalStatus.REJECTED, "Other; see comment", true),
                    code("RETURN_PAYSLIP", InternalApprovalStatus.RETURNED, "Payslip missing, unclear or out of date", true))
            .collect(Collectors.toMap(CreditReasonCode::getCode, Function.identity()));

    private LoanRepository loanRepository;
    private AuditService auditService;
    private LoanNotificationService loanNotificationService;
    private LoanMapper loanMapper;
    private AuthService authService;
    private CreditDecisionRepository creditDecisionRepository;
    private CreditReasonCodeRepository creditReasonCodeRepository;
    private LoanDocumentRepository loanDocumentRepository;
    private User approver;
    private CreditDecisionServiceImpl service;

    private static CreditReasonCode code(String code, InternalApprovalStatus decision, String description, boolean active) {
        return CreditReasonCode.builder().code(code).decision(decision).description(description).active(active).build();
    }

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        auditService = mock(AuditService.class);
        loanNotificationService = mock(LoanNotificationService.class);
        authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("credit.manager");
        approver = new User();
        approver.setUsername("credit.manager");
        approver.setIdNumber("639999999Z99");
        approver.setMobileNumber("263771111111");
        when(authService.getLoggedInUser()).thenReturn(approver);
        loanMapper = mock(LoanMapper.class);
        when(loanMapper.toResponse(any())).thenReturn(new LoanResponse());
        creditDecisionRepository = mock(CreditDecisionRepository.class);
        creditReasonCodeRepository = mock(CreditReasonCodeRepository.class);
        when(creditReasonCodeRepository.findById(anyString()))
                .thenAnswer(i -> Optional.ofNullable(REASON_CODES.get(i.<String>getArgument(0))));
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        loanDocumentRepository = mock(LoanDocumentRepository.class);
        service = new CreditDecisionServiceImpl(loanRepository, authService, loanMapper, loanNotificationService,
                new DeductionCancellationService(loanRepository, auditService, authService), auditService,
                creditDecisionRepository, creditReasonCodeRepository,
                new CreditDecisionLog(creditDecisionRepository, loanDocumentRepository), loanDocumentRepository,
                mock(PlatformTransactionManager.class));
    }

    private static CreditDecisionRequest decide(InternalApprovalStatus status) {
        String reasonCode = switch (status) {
            case APPROVED -> "APPROVE_WITHIN_POLICY";
            case REJECTED -> "REJECT_OTHER";
            case RETURNED -> "RETURN_PAYSLIP";
            case PENDING -> "APPROVE_WITHIN_POLICY";
        };
        return new CreditDecisionRequest(status, reasonCode, "Checked against the payslip");
    }

    private Loan given(LoanApprovalStatus payroll, InternalApprovalStatus internal) {
        Loan loan = Loan.builder().loanApprovalStatus(payroll).internalApprovalStatus(internal)
                .batchNumber("BATCH-20260901-07").ecNumber("1234567A")
                .firstName("Rudo").lastName("Chikwanha")
                .nationalIdNumber("631234567A42")
                .mobileNumber("+263782606983")
                .principal(new BigDecimal("531.91")).disbursedAmount(new BigDecimal("500.00")).tenor(3)
                .createdBy("agent.moyo")
                .merchant(Merchant.builder().merchantCode("INNBUCKS").companyName("Innbucks")
                        .disbursementType(DisbursementType.CUSTOMER_MOBILE_WALLET).build())
                .build();
        loan.setId(42L);
        when(loanRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));
        return loan;
    }

    private List<AuditLog> audited() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    /** The one message the applicant was sent, as it reads. */
    private String sentSms() {
        ArgumentCaptor<Loan> sentFor = ArgumentCaptor.forClass(Loan.class);
        ArgumentCaptor<LoanNotice> notice = ArgumentCaptor.forClass(LoanNotice.class);
        verify(loanNotificationService).notify(sentFor.capture(), notice.capture());
        assertThat(sentFor.getValue().getMobileNumber()).isEqualTo("+263782606983");
        return notice.getValue().textFor(sentFor.getValue());
    }

    private CreditDecision logged() {
        ArgumentCaptor<CreditDecision> entry = ArgumentCaptor.forClass(CreditDecision.class);
        verify(creditDecisionRepository).save(entry.capture());
        return entry.getValue();
    }

    @Test
    @DisplayName("an unknown loan is a NotFoundException (404), not NoSuchElementException (500)")
    void unknownLoanIsNotFound() {
        when(loanRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.decide(7L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 7 not found");
    }

    @Test
    @DisplayName("PENDING is not a decision")
    void pendingIsNotADecision() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.PENDING)))
                .isInstanceOf(LoanApprovalException.class)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid status: a decision must be APPROVED, REJECTED or RETURNED");
    }

    @Test
    @DisplayName("a decided loan names the decision it already holds — approved OR rejected")
    void alreadyDecidedNamesTheDecision() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.REJECTED);

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Loan has already been rejected");
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(creditDecisionRepository);
    }

    @Test
    @DisplayName("a loan not yet payroll-approved cannot be signed off")
    void notPayrollApprovedIsRefused() {
        given(LoanApprovalStatus.NEW, InternalApprovalStatus.PENDING);

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Loan with status NEW cannot be approved");
    }

    // ── Reason codes (FR-PBL-027) ────────────────────────────────────────────

    @Nested
    @DisplayName("every decision names an active reason code for that decision")
    class ReasonCodes {

        @Test
        @DisplayName("an unknown code is refused, and nothing is recorded")
        void unknownCode() {
            Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

            assertThatThrownBy(() -> service.decide(42L,
                    new CreditDecisionRequest(InternalApprovalStatus.APPROVED, "APPROVE_OK", "Fine")))
                    .isInstanceOf(LoanApprovalException.class)
                    .hasMessage("Unknown reason code APPROVE_OK");
            assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.PENDING);
            verify(loanRepository, never()).save(any());
            verifyNoInteractions(creditDecisionRepository, loanNotificationService);
        }

        @Test
        @DisplayName("an inactive (legacy) code cannot be chosen")
        void inactiveCode() {
            given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

            assertThatThrownBy(() -> service.decide(42L,
                    new CreditDecisionRequest(InternalApprovalStatus.APPROVED, "LEGACY_APPROVAL", "Fine")))
                    .isInstanceOf(LoanApprovalException.class)
                    .hasMessage("Unknown reason code LEGACY_APPROVAL");
        }

        @Test
        @DisplayName("a code for another decision is refused, naming both")
        void codeForAnotherDecision() {
            given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

            assertThatThrownBy(() -> service.decide(42L,
                    new CreditDecisionRequest(InternalApprovalStatus.APPROVED, "RETURN_PAYSLIP", "Fine")))
                    .isInstanceOf(LoanApprovalException.class)
                    .hasMessage("Reason code RETURN_PAYSLIP is for RETURNED decisions, not APPROVED");
            verify(loanRepository, never()).save(any());
        }

        @Test
        @DisplayName("a blank reason code or comment is refused before the loan is touched")
        void blankReasonOrComment() {
            Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

            assertThatThrownBy(() -> service.decide(42L,
                    new CreditDecisionRequest(InternalApprovalStatus.APPROVED, " ", "Fine")))
                    .isInstanceOf(LoanApprovalException.class)
                    .hasMessage("Reason code is required");
            assertThatThrownBy(() -> service.decide(42L,
                    new CreditDecisionRequest(InternalApprovalStatus.APPROVED, "APPROVE_WITHIN_POLICY", null)))
                    .isInstanceOf(LoanApprovalException.class)
                    .hasMessage("Comment is required");
            // Refused before the payee is frozen, not rolled back after.
            assertThat(loan.getApprovedDisbursementType()).isNull();
            verify(loanRepository, never()).save(any());
            verifyNoInteractions(creditDecisionRepository);
        }

        @Test
        @DisplayName("case and surrounding spaces do not matter; the loan keeps the canonical code")
        void codeIsNormalised() {
            Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

            service.decide(42L, new CreditDecisionRequest(InternalApprovalStatus.APPROVED,
                    " approve_within_policy ", "  Verified  "));

            assertThat(loan.getInternalApprovalReasonCode()).isEqualTo("APPROVE_WITHIN_POLICY");
            assertThat(loan.getInternalApprovalComment()).isEqualTo("Verified");
            assertThat(logged().getReasonCode()).isEqualTo("APPROVE_WITHIN_POLICY");
        }
    }

    // ── The decision log (FR-PBL-032) ────────────────────────────────────────

    @Test
    @DisplayName("a decision is logged with who, when, why and the loan data it was based on")
    void decisionIsLogged() throws Exception {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setMerchant(Merchant.builder().merchantCode("MEGA").companyName("Mega Furnishers")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).accountNumber("0771000001").build());
        loan.setEmploymentDetail(new EmploymentDetail());
        loan.getEmploymentDetail().setEmployerName("Government of Zimbabwe");
        loan.getEmploymentDetail().setMinistry("Ministry of Health and Child Care");
        loan.getEmploymentDetail().setGrossSalary(new BigDecimal("850.00"));
        loan.getEmploymentDetail().setNetSalary(new BigDecimal("620.00"));
        loan.setPayslipDeductions(new ArrayList<>(List.of(new PayslipDeduction("ZIMRA PAYE", new BigDecimal("142.50")))));
        LocalDateTime uploaded = LocalDateTime.of(2026, 9, 1, 8, 0);
        when(loanDocumentRepository.findSummaries(42L)).thenReturn(List.of(
                new LoanDocumentSummary(DocumentType.PAYSLIP, 1, DocumentOrigin.APPLICATION, "application/pdf", 9,
                        "a".repeat(64), null, "agent.moyo", uploaded),
                new LoanDocumentSummary(DocumentType.PAYSLIP, 2, DocumentOrigin.AMENDMENT, "application/pdf", 9,
                        "b".repeat(64), "Payslip for August", "agent.moyo", uploaded.plusDays(1)),
                new LoanDocumentSummary(DocumentType.SIGNATURE, 1, DocumentOrigin.APPLICATION, "image/png", 9,
                        "c".repeat(64), null, "agent.moyo", uploaded)));

        service.decide(42L, decide(InternalApprovalStatus.APPROVED));

        CreditDecision entry = logged();
        assertThat(entry.getLoanId()).isEqualTo(42L);
        assertThat(entry.getAction()).isEqualTo(CreditAction.APPROVED);
        assertThat(entry.getReasonCode()).isEqualTo("APPROVE_WITHIN_POLICY");
        assertThat(entry.getComment()).isEqualTo("Checked against the payslip");
        assertThat(entry.getPerformedBy()).isEqualTo("credit.manager");
        // The same instant as the decision on the loan.
        assertThat(entry.getPerformedAt()).isEqualTo(loan.getInternalApprovalDate()).isNotNull();
        assertThat(entry.getSnapshotSha256()).isEqualTo(AuditService.sha256Hex(entry.getLoanSnapshot()));

        JsonNode snapshot = JSON.readTree(entry.getLoanSnapshot());
        assertThat(snapshot.path("reference").asString()).isEqualTo("000000042");
        assertThat(snapshot.path("originator").asString()).isEqualTo("agent.moyo");
        assertThat(snapshot.path("principal").decimalValue()).isEqualByComparingTo("531.91");
        assertThat(snapshot.path("employment").path("ministry").asString()).isEqualTo("Ministry of Health and Child Care");
        assertThat(snapshot.path("employment").path("netSalary").decimalValue()).isEqualByComparingTo("620.00");
        assertThat(snapshot.path("payslipDeductions").get(0).path("beneficiary").asString()).isEqualTo("ZIMRA PAYE");
        // The payee as frozen by this approval, masked.
        assertThat(snapshot.path("payoutType").asString()).isEqualTo("MERCHANT_MOBILE_WALLET");
        assertThat(snapshot.path("payoutAccount").asString()).isEqualTo("****0001");
        // Each document by the hash of its CURRENT version, never its content.
        assertThat(snapshot.path("documents").path("payslipPictureSha256").asString()).isEqualTo("b".repeat(64));
        assertThat(snapshot.path("documents").path("signatureSha256").asString()).isEqualTo("c".repeat(64));
        assertThat(snapshot.path("documents").path("nationalIdPictureSha256").isNull()).isTrue();
        assertThat(snapshot.path("documents").path("witnessSignatureSha256").isNull()).isTrue();
        assertThat(entry.getLoanSnapshot()).doesNotContain("631234567A42", "782606983", "0771000001");
    }

    @Test
    @DisplayName("the same loan data always makes the same snapshot, so its hash can be checked later")
    void snapshotIsDeterministic() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

        Map<DocumentType, String> fingerprints = Map.of(DocumentType.PAYSLIP, "a".repeat(64));
        assertThat(CreditDecisionSnapshot.of(loan, fingerprints).toJson())
                .isEqualTo(CreditDecisionSnapshot.of(loan, Map.copyOf(fingerprints)).toJson());
    }

    @Test
    @DisplayName("a credit REJECT flags the lodged deduction for cancellation, audited, before the save")
    void creditRejectFlagsDeductionCancellation() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanRepository.save(any())).thenAnswer(i -> {
            // Saved together with the decision, not in a second write.
            assertThat(i.<Loan>getArgument(0).getDeductionCancellationStatus())
                    .isEqualTo(DeductionCancellationStatus.REQUIRED);
            return i.getArgument(0);
        });

        service.decide(42L, decide(InternalApprovalStatus.REJECTED));

        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("CREDIT_REJECTED");
        assertThat(loan.getDeductionCancellationRequestedAt()).isNotNull();
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(captor.capture());
        AuditLog audit = captor.getValue().build();
        assertThat(audit.getEventType()).isEqualTo("DEDUCTION_CANCELLATION_REQUIRED");
        assertThat(audit.getEntityId()).isEqualTo("42");
        assertThat(audit.getActorId()).isEqualTo("credit.manager");
        assertThat(audit.getCorrelationId()).isEqualTo("BATCH-20260901-07");
        assertThat(audit.getDetail()).contains("reason=CREDIT_REJECTED", "ecNumber=*****67A")
                .doesNotContain("1234567A");
        assertThat(logged().getAction()).isEqualTo(CreditAction.REJECTED);
    }

    @Test
    @DisplayName("a credit APPROVE leaves the deduction alone — it is what repays the loan")
    void creditApproveDoesNotFlag() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

        service.decide(42L, decide(InternalApprovalStatus.APPROVED));

        assertThat(loan.getDeductionCancellationStatus()).isNull();
        assertThat(audited()).extracting(AuditLog::getEventType).containsExactly("CREDIT_APPROVED");
    }

    @Test
    @DisplayName("a valid decision is saved and mapped for the API")
    void validDecisionIsSaved() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        LoanResponse view = new LoanResponse();
        when(loanMapper.toResponse(any())).thenReturn(view);

        LoanResponse response = service.decide(42L, decide(InternalApprovalStatus.APPROVED));

        assertThat(response).isSameAs(view);
        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        verify(loanMapper).toResponse(saved.getValue());
        assertThat(saved.getValue().getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.APPROVED);
        assertThat(saved.getValue().getInternalApprovalReasonCode()).isEqualTo("APPROVE_WITHIN_POLICY");
        assertThat(saved.getValue().getInternalApprovalBy()).isEqualTo("credit.manager");
    }

    @Test
    @DisplayName("the reviewer's comment is kept on the loan and never sent to the customer")
    void rejectionSmsCarriesNoReviewerComment() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

        service.decide(42L, new CreditDecisionRequest(InternalApprovalStatus.REJECTED, "REJECT_OTHER",
                "Payslip looks edited: DTI 62%, do not re-apply!"));

        assertThat(loan.getInternalApprovalComment()).isEqualTo("Payslip looks edited: DTI 62%, do not re-apply!");
        assertThat(sentSms())
                .isEqualTo("We regret to inform you that your loan application with ref # 000000042 "
                        + "has been declined. Please contact Innbucks for more information.")
                .doesNotContain("Payslip");
    }

    // ── Return for more information ──────────────────────────────────────────

    @Nested
    @DisplayName("return for more information")
    class Return {

        @Test
        @DisplayName("RETURNED is recorded and logged, the customer is told, and the deduction is left alone")
        void returnIsRecorded() {
            Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

            service.decide(42L, decide(InternalApprovalStatus.RETURNED));

            assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.RETURNED);
            assertThat(loan.getInternalApprovalReasonCode()).isEqualTo("RETURN_PAYSLIP");
            assertThat(loan.getDeductionCancellationStatus()).isNull();
            // Nothing is frozen: a return pays nothing.
            assertThat(loan.getApprovedDisbursementType()).isNull();
            assertThat(logged().getAction()).isEqualTo(CreditAction.RETURNED);
            assertThat(sentSms()).isEqualTo("Your loan application with ref # 000000042 needs more information"
                    + " before a decision can be made. Innbucks or your agent will contact you.");
            assertThat(audited()).isEmpty();
        }

        @Test
        @DisplayName("the originator may return their own loan: a return pays nothing")
        void originatorMayReturn() {
            Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
            loan.setCreatedBy("credit.manager");

            service.decide(42L, decide(InternalApprovalStatus.RETURNED));

            assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.RETURNED);
        }

        @Test
        @DisplayName("a returned loan cannot be approved or returned again until it is resubmitted")
        void returnedLoanWaitsForTheOriginator() {
            given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.RETURNED);

            for (InternalApprovalStatus decision : List.of(InternalApprovalStatus.APPROVED, InternalApprovalStatus.RETURNED)) {
                assertThatThrownBy(() -> service.decide(42L, decide(decision)))
                        .isInstanceOf(LoanApprovalException.class)
                        .hasMessage("Loan was returned for more information and has not been resubmitted;"
                                + " it can only be rejected");
            }
            verify(loanRepository, never()).save(any());
        }

        @Test
        @DisplayName("an unanswered return can be closed by rejecting it, which flags the deduction")
        void returnedLoanCanBeRejected() {
            Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.RETURNED);

            service.decide(42L, decide(InternalApprovalStatus.REJECTED));

            assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
            assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        }
    }

    @Nested
    @DisplayName("resubmission")
    class Resubmission {

        private final LoanReadScope agentScope = LoanReadScope.originator("harare-motors", 7L);

        @Test
        @DisplayName("puts a returned loan back in the credit queue, undecided, and logs the answer")
        void resubmitReturnsTheLoanToTheQueue() {
            Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.RETURNED);
            loan.setInternalApprovalReasonCode("RETURN_PAYSLIP");
            loan.setInternalApprovalComment("Send the August payslip");
            loan.setInternalApprovalBy("credit.manager");
            // The earlier wait was escalated; the answer starts a new wait, not yet escalated (FR-PBL-030).
            loan.setCreditEscalatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
            when(authService.getLoggedInUsername()).thenReturn("agent.moyo");
            when(loanRepository.exists(any(Specification.class))).thenReturn(true);

            service.resubmit(42L, new CreditResubmissionRequest("  August payslip checked with the bursar "), agentScope);

            assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.PENDING);
            assertThat(loan.getCreditEscalatedAt()).isNull();
            assertThat(loan.getCreditResubmittedAt()).isNotNull();
            assertThat(loan.creditQueueEnteredAt()).isEqualTo(loan.getCreditResubmittedAt());
            assertThat(loan.getInternalApprovalReasonCode()).isNull();
            assertThat(loan.getInternalApprovalComment()).isNull();
            assertThat(loan.getInternalApprovalBy()).isNull();
            assertThat(loan.getInternalApprovalDate()).isNull();
            CreditDecision entry = logged();
            assertThat(entry.getAction()).isEqualTo(CreditAction.RESUBMITTED);
            assertThat(entry.getReasonCode()).isNull();
            assertThat(entry.getComment()).isEqualTo("August payslip checked with the bursar");
            assertThat(entry.getPerformedBy()).isEqualTo("agent.moyo");
            assertThat(entry.getPerformedAt()).isEqualTo(loan.getCreditResubmittedAt());
            verify(loanNotificationService).notify(loan, LoanNotice.RESUBMITTED);
        }

        @Test
        @DisplayName("only a returned loan can be resubmitted")
        void onlyAReturnedLoan() {
            given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

            assertThatThrownBy(() -> service.resubmit(42L, new CreditResubmissionRequest("Here"),
                    LoanReadScope.platform()))
                    .isInstanceOf(LoanApprovalException.class)
                    .hasMessage("Loan is not waiting for more information (credit status PENDING)");
            verify(loanRepository, never()).save(any());
            verifyNoInteractions(creditDecisionRepository);
        }

        @Test
        @DisplayName("a loan outside the caller's scope is not found, and not touched")
        void outOfScopeIsNotFound() {
            Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.RETURNED);
            when(loanRepository.exists(any(Specification.class))).thenReturn(false);

            assertThatThrownBy(() -> service.resubmit(42L, new CreditResubmissionRequest("Here"), agentScope))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Loan 42 not found");
            assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.RETURNED);
            verify(loanRepository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("platform-wide staff are not narrowed")
        void platformScopeIsNotNarrowed() {
            given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.RETURNED);

            service.resubmit(42L, new CreditResubmissionRequest("Here"), LoanReadScope.platform());

            verify(loanRepository, never()).exists(any(Specification.class));
            assertThat(logged().getAction()).isEqualTo(CreditAction.RESUBMITTED);
        }
    }

    // ── Segregation of duties (FR-PBL-029) ───────────────────────────────────

    @Test
    @DisplayName("whoever originated a loan cannot approve it: refused (403) before anything is recorded")
    void originatorCannotApprove() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setCreatedBy("Credit.Manager");

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Loan 000000042 was originated by credit.manager, who cannot also approve it;"
                        + " another credit officer must");
        assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.PENDING);
        assertThat(loan.getApprovedDisbursementType()).isNull();
        verify(loanRepository, never()).save(any());
        verify(creditDecisionRepository, never()).save(any());
        verifyNoInteractions(loanNotificationService, auditService);
    }

    @Test
    @DisplayName("the originating user account counts too, whatever the createdBy text says")
    void originatingUserCannotApprove() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        User originator = new User();
        originator.setUsername("credit.manager");
        loan.setCreatedByUser(originator);

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(AccessDeniedException.class);
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("whoever resubmitted a loan cannot approve it")
    void resubmitterCannotApprove() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(creditDecisionRepository.existsByLoanIdAndActionAndPerformedByIgnoreCase(
                42L, CreditAction.RESUBMITTED, "credit.manager")).thenReturn(true);

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Loan 000000042 was resubmitted by credit.manager, who cannot also approve it;"
                        + " another credit officer must");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("whoever replaced one of a loan's documents cannot approve it")
    void amenderCannotApprove() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanDocumentRepository.existsByLoanIdAndOriginAndUploadedByIgnoreCase(
                42L, DocumentOrigin.AMENDMENT, "credit.manager")).thenReturn(true);

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Loan 000000042 has documents amended by credit.manager, who cannot also approve it;"
                        + " another credit officer must");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("whoever replaced a document may still reject the loan: a refusal pays nothing")
    void amenderMayReject() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanDocumentRepository.existsByLoanIdAndOriginAndUploadedByIgnoreCase(
                42L, DocumentOrigin.AMENDMENT, "credit.manager")).thenReturn(true);

        service.decide(42L, decide(InternalApprovalStatus.REJECTED));

        assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
    }

    @Test
    @DisplayName("a loan held for payslip review cannot be approved until the review clears it")
    void heldForPayslipReviewCannotBeApproved() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setPayslipReviewStatus(PayslipReviewStatus.PENDING);

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Loan 000000042 is held for payslip review and cannot be approved until it is cleared");
        verify(loanRepository, never()).save(any());
        verify(creditDecisionRepository, never()).save(any());
    }

    @Test
    @DisplayName("a loan held for an employment event cannot be approved until it is released, and can be rejected")
    void heldForAnEmploymentEventCannotBeApproved() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        when(loanRepository.isHeldForEmploymentEvent(42L)).thenReturn(true);

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Loan 000000042 is held for an employment event and cannot be approved until it is released");
        verify(loanRepository, never()).save(any());
        verify(creditDecisionRepository, never()).save(any());

        service.decide(42L, decide(InternalApprovalStatus.REJECTED));
        assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
    }

    @Test
    @DisplayName("a party to the loan cannot approve it")
    void partyCannotApprove() {
        given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        approver.setIdNumber("63-1234567-a-42");

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("credit.manager is a party to loan 000000042 and cannot approve it;"
                        + " another credit officer must");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("the originator may still reject: a refusal pays nothing")
    void originatorMayReject() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setCreatedBy("credit.manager");
        approver.setIdNumber("631234567A42");

        service.decide(42L, decide(InternalApprovalStatus.REJECTED));

        assertThat(loan.getInternalApprovalStatus()).isEqualTo(InternalApprovalStatus.REJECTED);
    }

    @Nested
    @DisplayName("a party to the loan")
    class PartyTo {

        private Loan loan() {
            NextOfKin nextOfKin = new NextOfKin();
            nextOfKin.setNationalId("63-7654321-C-42");
            nextOfKin.setMobileNumber("+263772345678");
            Loan loan = Loan.builder().nationalIdNumber("631234567A42").mobileNumber("263782606983")
                    .walletNumber("263712345678").nextOfKin(nextOfKin).build();
            loan.setId(42L);
            return loan;
        }

        private User user(String idNumber, String mobileNumber) {
            User user = new User();
            user.setIdNumber(idNumber);
            user.setMobileNumber(mobileNumber);
            return user;
        }

        @Test
        @DisplayName("is the applicant, by ID number however it is typed")
        void applicantById() {
            assertThat(SegregationOfDuties.isPartyTo(loan(), user("63-1234567 a 42", null))).isTrue();
        }

        @Test
        @DisplayName("is the applicant, or holds the payout wallet, by mobile number however it is typed")
        void applicantOrWalletByMobile() {
            assertThat(SegregationOfDuties.isPartyTo(loan(), user(null, "0782606983"))).isTrue();
            assertThat(SegregationOfDuties.isPartyTo(loan(), user(null, "+263 71 234 5678"))).isTrue();
        }

        @Test
        @DisplayName("is the next of kin, by ID or mobile")
        void nextOfKin() {
            assertThat(SegregationOfDuties.isPartyTo(loan(), user("637654321C42", null))).isTrue();
            assertThat(SegregationOfDuties.isPartyTo(loan(), user(null, "0772345678"))).isTrue();
        }

        @Test
        @DisplayName("is not an unrelated officer, nor one with no ID or mobile on file")
        void unrelated() {
            assertThat(SegregationOfDuties.isPartyTo(loan(), user("639999999Z99", "263771111111"))).isFalse();
            assertThat(SegregationOfDuties.isPartyTo(loan(), user(null, null))).isFalse();
            assertThat(SegregationOfDuties.isPartyTo(loan(), user(" ", "12"))).isFalse();
            assertThat(SegregationOfDuties.isPartyTo(loan(), null)).isFalse();
        }
    }

    // ── The payee frozen at approval ─────────────────────────────────────────

    @Test
    @DisplayName("approval freezes the merchant's settlement account, and the audit names it masked")
    void approvalFreezesTheMerchantPayee() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setMerchant(Merchant.builder().merchantCode("MEGA").companyName("Mega Furnishers")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).accountNumber("0771000001").build());
        when(loanRepository.save(any())).thenAnswer(i -> {
            // Frozen in the same write as the decision.
            assertThat(i.<Loan>getArgument(0).getApprovedSettlementAccount()).isEqualTo("0771000001");
            return i.getArgument(0);
        });

        service.decide(42L, decide(InternalApprovalStatus.APPROVED));

        assertThat(loan.getApprovedDisbursementType()).isEqualTo(DisbursementType.MERCHANT_MOBILE_WALLET);
        assertThat(loan.getApprovedSettlementAccount()).isEqualTo("0771000001");
        AuditLog audit = audited().getFirst();
        assertThat(audit.getEventType()).isEqualTo("CREDIT_APPROVED");
        assertThat(audit.getActorId()).isEqualTo("credit.manager");
        assertThat(audit.getDetail()).contains("originator=agent.moyo", "payoutType=MERCHANT_MOBILE_WALLET",
                "settlementAccount=****0001", "merchant=MEGA").doesNotContain("0771000001");

        // A later edit to the merchant row does not move the loan's money.
        loan.getMerchant().setAccountNumber("0779999999");
        PayoutDestination payee = PayoutDestination.of(loan);
        assertThat(payee.merchantAccount()).isEqualTo("0771000001");
        assertThat(payee.frozen()).isTrue();
        assertThat(payee.differsFrom(loan.getMerchant())).isTrue();
    }

    @Test
    @DisplayName("a customer-wallet loan freezes the type and no merchant account")
    void approvalFreezesTheCustomerWallet() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);

        service.decide(42L, decide(InternalApprovalStatus.APPROVED));

        assertThat(loan.getApprovedDisbursementType()).isEqualTo(DisbursementType.CUSTOMER_MOBILE_WALLET);
        assertThat(loan.getApprovedSettlementAccount()).isNull();
        assertThat(audited().getFirst().getDetail()).doesNotContain("settlementAccount");
    }

    @Test
    @DisplayName("a merchant loan with no settlement account cannot be approved: there is nowhere to pay it")
    void noSettlementAccountIsRefused() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setMerchant(Merchant.builder().companyName("Mega Furnishers")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).accountNumber(" ").build());

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Merchant Mega Furnishers has no settlement account, so loan 000000042 cannot be paid");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("a loan whose merchant has no payout type cannot be approved")
    void noPayoutTypeIsRefused() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING);
        loan.setMerchant(null);

        assertThatThrownBy(() -> service.decide(42L, decide(InternalApprovalStatus.APPROVED)))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("Loan 000000042 has no merchant payout type, so there is nowhere to pay it");
    }

    @Test
    @DisplayName("a loan approved before the freeze existed still pays per the merchant's live settings")
    void legacyLoanFallsBackToTheLiveMerchant() {
        Loan loan = given(LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED);
        loan.setMerchant(Merchant.builder().disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET)
                .accountNumber("0771000001").build());

        PayoutDestination payee = PayoutDestination.of(loan);

        assertThat(payee.frozen()).isFalse();
        assertThat(payee.merchantAccount()).isEqualTo("0771000001");
        assertThat(payee.differsFrom(loan.getMerchant())).isFalse();
    }

    // ── Reading the log and the codes ────────────────────────────────────────

    @Test
    @DisplayName("the history is the loan's log, oldest first, with each code's description")
    void historyNamesTheReasons() {
        when(loanRepository.existsById(42L)).thenReturn(true);
        CreditDecision returned = CreditDecision.builder().id(1L).loanId(42L).action(CreditAction.RETURNED)
                .reasonCode("RETURN_PAYSLIP").comment("Send August").performedBy("credit.manager")
                .loanSnapshot("{\"reference\":\"000000042\"}").snapshotSha256("abc").build();
        CreditDecision resubmitted = CreditDecision.builder().id(2L).loanId(42L).action(CreditAction.RESUBMITTED)
                .comment("Sent").performedBy("agent.moyo").loanSnapshot("{}").snapshotSha256("def").build();
        when(creditDecisionRepository.findByLoanIdOrderByIdAsc(42L)).thenReturn(List.of(returned, resubmitted));
        when(creditReasonCodeRepository.findAllById(anyIterable()))
                .thenReturn(List.of(REASON_CODES.get("RETURN_PAYSLIP")));

        List<CreditDecisionResponse> history = service.history(42L);

        assertThat(history).extracting(CreditDecisionResponse::action)
                .containsExactly(CreditAction.RETURNED, CreditAction.RESUBMITTED);
        assertThat(history.getFirst().reasonDescription()).isEqualTo("Payslip missing, unclear or out of date");
        assertThat(history.getFirst().loanSnapshot()).isEqualTo("{\"reference\":\"000000042\"}");
        assertThat(history.get(1).reasonCode()).isNull();
        assertThat(history.get(1).reasonDescription()).isNull();
    }

    @Test
    @DisplayName("the history of an unknown loan is a 404")
    void historyOfUnknownLoan() {
        when(loanRepository.existsById(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.history(7L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 7 not found");
    }

    @Test
    @DisplayName("the reason codes on offer are the active ones, for one decision or all")
    void reasonCodesOnOffer() {
        when(creditReasonCodeRepository.findByActiveTrueAndDecisionOrderByDisplayOrderAsc(InternalApprovalStatus.RETURNED))
                .thenReturn(List.of(REASON_CODES.get("RETURN_PAYSLIP")));
        when(creditReasonCodeRepository.findByActiveTrueOrderByDecisionAscDisplayOrderAsc())
                .thenReturn(List.of(REASON_CODES.get("APPROVE_WITHIN_POLICY"), REASON_CODES.get("RETURN_PAYSLIP")));

        assertThat(service.reasonCodes(InternalApprovalStatus.RETURNED))
                .containsExactly(new CreditReasonCodeResponse("RETURN_PAYSLIP", InternalApprovalStatus.RETURNED,
                        "Payslip missing, unclear or out of date"));
        assertThat(service.reasonCodes(null)).extracting(CreditReasonCodeResponse::code)
                .containsExactly("APPROVE_WITHIN_POLICY", "RETURN_PAYSLIP");
    }
}
