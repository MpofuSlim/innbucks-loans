package zw.co.innbucks.loans.core.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.domain.Specification;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.LoanApprovalException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.files.FileSignatureValidator;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.PayslipFraudDetector;
import zw.co.innbucks.loans.core.loan.PayslipFraudReason;
import zw.co.innbucks.loans.core.loan.PayslipReviewService;
import zw.co.innbucks.loans.core.loan.PayslipReviewStatus;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A loan's documents with version history and an access log (FR-SSB-009): every hand-out of content is
 * logged, a replacement is a new version beside the old ones, only a payslip or national ID may be
 * replaced and only while Credit may still ask for one, and a replaced payslip is checked for fraud again
 * (FR-SSB-007). An out-of-scope loan reads as missing, as on every other loan read.
 */
class LoanDocumentServiceTest {

    /** Real files: a replacement is opened and checked like any upload (FR-SSB-005). */
    private static final byte[] OLD_PAYSLIP = TestDocuments.pdf(1);
    private static final byte[] NEW_PAYSLIP = TestDocuments.pdf(2);
    private static final LoanReadScope PLATFORM = LoanReadScope.platform();
    private static final LoanReadScope AGENT = LoanReadScope.originator("MEGA", 7L);

    private LoanDocumentRepository loanDocumentRepository;
    private LoanDocumentAccessRepository loanDocumentAccessRepository;
    private LoanRepository loanRepository;
    private PayslipFraudDetector payslipFraudDetector;
    private PayslipReviewService payslipReviewService;
    private LoanDocumentService service;

    @BeforeEach
    void setUp() {
        loanDocumentRepository = mock(LoanDocumentRepository.class);
        loanDocumentAccessRepository = mock(LoanDocumentAccessRepository.class);
        loanRepository = mock(LoanRepository.class);
        payslipFraudDetector = mock(PayslipFraudDetector.class);
        payslipReviewService = mock(PayslipReviewService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("agent.moyo");
        when(loanDocumentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(loanRepository.existsById(42L)).thenReturn(true);
        service = new LoanDocumentService(loanDocumentRepository, loanDocumentAccessRepository, loanRepository,
                new DocumentInspector(new FileSignatureValidator(), new DocumentUploadProperties()), authService,
                payslipFraudDetector, payslipReviewService);
    }

    private static LoanDocument document(long id, DocumentType type, int version, byte[] content) {
        return LoanDocument.builder().id(id).loanId(42L).documentType(type).version(version)
                .origin(version == 1 ? DocumentOrigin.APPLICATION : DocumentOrigin.AMENDMENT)
                .content(content).contentType("application/pdf").sizeBytes(content.length)
                .sha256(AuditService.sha256Hex(content)).reason(version == 1 ? null : "Newer copy")
                .uploadedBy("agent.moyo").uploadedAt(LocalDateTime.of(2026, 9, 1, 8, 0)).build();
    }

    private Loan loanOnFile(InternalApprovalStatus credit, LoanApprovalStatus ssb) {
        Loan loan = Loan.builder().internalApprovalStatus(credit).loanApprovalStatus(ssb)
                .payslipSha256(AuditService.sha256Hex(OLD_PAYSLIP)).createdBy("agent.moyo").build();
        loan.setId(42L);
        when(loanRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));
        when(loanDocumentRepository.findFirstByLoanIdAndDocumentTypeOrderByVersionDesc(42L, DocumentType.PAYSLIP))
                .thenReturn(Optional.of(document(1, DocumentType.PAYSLIP, 1, OLD_PAYSLIP)));
        return loan;
    }

    private static AmendDocumentRequest amendment(byte[] content) {
        return new AmendDocumentRequest(Base64.getEncoder().encodeToString(content), "  August payslip, as asked ");
    }

    private List<LoanDocumentAccess> logged() {
        ArgumentCaptor<LoanDocumentAccess> access = ArgumentCaptor.forClass(LoanDocumentAccess.class);
        verify(loanDocumentAccessRepository, atLeast(0)).save(access.capture());
        return access.getAllValues();
    }

    @Test
    @DisplayName("viewing a document hands out its content and logs the view: who, which version, when")
    void viewIsLogged() {
        when(loanDocumentRepository.findFirstByLoanIdAndDocumentTypeOrderByVersionDesc(42L, DocumentType.PAYSLIP))
                .thenReturn(Optional.of(document(11, DocumentType.PAYSLIP, 2, NEW_PAYSLIP)));

        LoanDocumentContent content = service.view(42L, DocumentType.PAYSLIP, null, PLATFORM);

        assertThat(Base64.getDecoder().decode(content.content())).isEqualTo(NEW_PAYSLIP);
        assertThat(content.version()).isEqualTo(2);
        assertThat(logged()).singleElement().satisfies(access -> {
            assertThat(access.getAction()).isEqualTo(DocumentAccessAction.VIEW);
            assertThat(access.getDocumentId()).isEqualTo(11L);
            assertThat(access.getVersion()).isEqualTo(2);
            assertThat(access.getPerformedBy()).isEqualTo("agent.moyo");
            assertThat(access.getPerformedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("an earlier version can be viewed too, and a version that does not exist is a 404 with nothing logged")
    void viewAVersion() {
        when(loanDocumentRepository.findByLoanIdAndDocumentTypeAndVersion(42L, DocumentType.PAYSLIP, 1))
                .thenReturn(Optional.of(document(10, DocumentType.PAYSLIP, 1, OLD_PAYSLIP)));

        assertThat(service.view(42L, DocumentType.PAYSLIP, 1, PLATFORM).version()).isEqualTo(1);
        assertThatThrownBy(() -> service.view(42L, DocumentType.PAYSLIP, 3, PLATFORM))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 42 has no PAYSLIP version 3");
        assertThatThrownBy(() -> service.view(42L, DocumentType.NATIONAL_ID, null, PLATFORM))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 42 has no NATIONAL_ID");
        assertThat(logged()).extracting(LoanDocumentAccess::getVersion).containsExactly(1);
    }

    @Test
    @DisplayName("a loan outside the caller's scope reads as missing, and nothing is looked up or logged")
    void outOfScopeIsMissing() {
        when(loanRepository.exists(any(Specification.class))).thenReturn(false);

        assertThatThrownBy(() -> service.view(42L, DocumentType.PAYSLIP, null, AGENT))
                .isInstanceOf(NotFoundException.class).hasMessage("Loan 42 not found");
        assertThatThrownBy(() -> service.history(42L, AGENT))
                .isInstanceOf(NotFoundException.class).hasMessage("Loan 42 not found");
        assertThatThrownBy(() -> service.amend(42L, DocumentType.PAYSLIP, amendment(NEW_PAYSLIP), AGENT))
                .isInstanceOf(NotFoundException.class).hasMessage("Loan 42 not found");
        verifyNoInteractions(loanDocumentRepository, loanDocumentAccessRepository);
        verify(loanRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("the version history lists every version without content, and is not itself an access")
    void historyIsNotAnAccess() {
        LoanDocumentSummary v1 = LoanDocumentSummary.of(document(10, DocumentType.PAYSLIP, 1, OLD_PAYSLIP));
        LoanDocumentSummary v2 = LoanDocumentSummary.of(document(11, DocumentType.PAYSLIP, 2, NEW_PAYSLIP));
        when(loanRepository.exists(any(Specification.class))).thenReturn(true);
        when(loanDocumentRepository.findSummaries(42L)).thenReturn(List.of(v1, v2));

        assertThat(service.history(42L, AGENT)).containsExactly(v1, v2);
        assertThat(service.currentSummaries(42L)).containsExactly(v2);
        verifyNoInteractions(loanDocumentAccessRepository);
    }

    @Test
    @DisplayName("a replacement payslip is the next version, kept beside the old one, with the reason and the upload logged")
    void amendAddsAVersion() {
        Loan loan = loanOnFile(InternalApprovalStatus.RETURNED, LoanApprovalStatus.APPROVED);

        LoanDocumentSummary saved = service.amend(42L, DocumentType.PAYSLIP, amendment(NEW_PAYSLIP), PLATFORM);

        assertThat(saved.version()).isEqualTo(2);
        assertThat(saved.origin()).isEqualTo(DocumentOrigin.AMENDMENT);
        assertThat(saved.reason()).isEqualTo("August payslip, as asked");
        assertThat(saved.uploadedBy()).isEqualTo("agent.moyo");
        assertThat(saved.sha256()).isEqualTo(AuditService.sha256Hex(NEW_PAYSLIP));
        // The loan's payslip fingerprint follows the current version; nothing is ever overwritten.
        assertThat(loan.getPayslipSha256()).isEqualTo(AuditService.sha256Hex(NEW_PAYSLIP));
        verify(loanRepository).save(loan);
        verify(loanRepository).lockApplicant("loan-application:payslip:" + AuditService.sha256Hex(NEW_PAYSLIP));
        assertThat(logged()).extracting(LoanDocumentAccess::getAction, LoanDocumentAccess::getVersion)
                .containsExactly(tuple(DocumentAccessAction.UPLOAD, 2));
        verifyNoInteractions(payslipReviewService);
    }

    @Test
    @DisplayName("a national ID replacement does not touch the payslip fingerprint or its fraud checks")
    void nationalIdAmendment() {
        Loan loan = loanOnFile(InternalApprovalStatus.PENDING, LoanApprovalStatus.NEW);
        byte[] png = TestDocuments.encode(TestDocuments.page(1200, 800), "png");

        LoanDocumentSummary saved = service.amend(42L, DocumentType.NATIONAL_ID, amendment(png), PLATFORM);

        // No national ID on file yet: the replacement is its first version.
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.contentType()).isEqualTo("image/png");
        assertThat(loan.getPayslipSha256()).isEqualTo(AuditService.sha256Hex(OLD_PAYSLIP));
        verifyNoInteractions(payslipFraudDetector, payslipReviewService);
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("the same file as the current version is a conflict: nothing is stored")
    void sameFileIsAConflict() {
        loanOnFile(InternalApprovalStatus.PENDING, LoanApprovalStatus.NEW);

        assertThatThrownBy(() -> service.amend(42L, DocumentType.PAYSLIP, amendment(OLD_PAYSLIP), PLATFORM))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This PAYSLIP is the same file as version 1");
        verify(loanDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a signature is part of the signed application and is never replaced")
    void signatureCannotBeReplaced() {
        assertThatThrownBy(() -> service.amend(42L, DocumentType.SIGNATURE, amendment(NEW_PAYSLIP), PLATFORM))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("SIGNATURE cannot be replaced: it is part of the signed application");
        assertThatThrownBy(() -> service.amend(42L, DocumentType.WITNESS_SIGNATURE, amendment(NEW_PAYSLIP), PLATFORM))
                .isInstanceOf(LoanApprovalException.class)
                .hasMessage("WITNESS_SIGNATURE cannot be replaced: it is part of the signed application");
        verifyNoInteractions(loanDocumentRepository);
    }

    @Test
    @DisplayName("once Credit has decided, or SSB has refused, the documents can no longer be replaced")
    void decidedLoansAreClosed() {
        loanOnFile(InternalApprovalStatus.APPROVED, LoanApprovalStatus.APPROVED);
        assertThatThrownBy(() -> service.amend(42L, DocumentType.PAYSLIP, amendment(NEW_PAYSLIP), PLATFORM))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Documents of loan 000000042 can no longer be replaced (SSB status APPROVED, credit status APPROVED)");

        loanOnFile(InternalApprovalStatus.PENDING, LoanApprovalStatus.REJECTED);
        assertThatThrownBy(() -> service.amend(42L, DocumentType.PAYSLIP, amendment(NEW_PAYSLIP), PLATFORM))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Documents of loan 000000042 can no longer be replaced (SSB status REJECTED, credit status PENDING)");
        verify(loanDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a file that is not a document is refused before anything is stored")
    void unsafeFileIsRefused() {
        loanOnFile(InternalApprovalStatus.PENDING, LoanApprovalStatus.NEW);

        assertThatThrownBy(() -> service.amend(42L, DocumentType.PAYSLIP,
                amendment(new byte[]{'M', 'Z', (byte) 0x90, 0x00}), PLATFORM))
                .isInstanceOf(DocumentRejectedException.class)
                .hasMessage("The payslip was refused: it is a program, not a document or photo. Please upload a PDF,"
                        + " PNG, JPEG or GIF file.")
                .satisfies(e -> assertThat(((DocumentRejectedException) e).getProblems()).singleElement()
                        .satisfies(problem -> {
                            assertThat(problem.field()).isEqualTo("content");
                            assertThat(problem.reason()).isEqualTo(DocumentProblemReason.EXECUTABLE);
                        }));
        verify(loanDocumentRepository, never()).save(any());
    }

    @Test
    @DisplayName("a replacement payslip already on another loan holds this one for review again, however it was cleared")
    void suspiciousReplacementIsHeld() {
        Loan loan = loanOnFile(InternalApprovalStatus.PENDING, LoanApprovalStatus.APPROVED);
        loan.setPayslipReviewStatus(PayslipReviewStatus.CLEARED);
        loan.setPayslipReviewedBy("credit.lead");
        loan.setPayslipReviewedAt(LocalDateTime.of(2026, 9, 2, 9, 0));
        loan.setPayslipReviewComment("Genuine");
        PayslipFraudDetector.Finding reused = new PayslipFraudDetector.Finding(
                PayslipFraudReason.PAYSLIP_REUSED_BY_ANOTHER_APPLICANT, 17L, "Same payslip file as loan 000000017");
        // The deductions finding is about captured figures the new file does not change: judged already.
        PayslipFraudDetector.Finding deductions = new PayslipFraudDetector.Finding(
                PayslipFraudReason.DEDUCTIONS_EXCEED_GROSS_LESS_NET, null, "Deductions total 450.00 but gross less net is 400.00");
        when(payslipFraudDetector.findingsFor(loan)).thenReturn(List.of(reused, deductions));

        service.amend(42L, DocumentType.PAYSLIP, amendment(NEW_PAYSLIP), PLATFORM);

        assertThat(loan.getPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.PENDING);
        assertThat(loan.getPayslipReviewedBy()).isNull();
        assertThat(loan.getPayslipReviewedAt()).isNull();
        assertThat(loan.getPayslipReviewComment()).isNull();
        verify(payslipReviewService).hold(eq(loan), eq(List.of(reused)));
    }

    @Test
    @DisplayName("a replacement raising only the deductions finding does not hold the loan again")
    void deductionsAloneDoNotReHold() {
        Loan loan = loanOnFile(InternalApprovalStatus.PENDING, LoanApprovalStatus.APPROVED);
        loan.setPayslipReviewStatus(PayslipReviewStatus.CLEARED);
        when(payslipFraudDetector.findingsFor(loan)).thenReturn(List.of(new PayslipFraudDetector.Finding(
                PayslipFraudReason.DEDUCTIONS_EXCEED_GROSS_LESS_NET, null, "Deductions total 450.00 but gross less net is 400.00")));

        service.amend(42L, DocumentType.PAYSLIP, amendment(NEW_PAYSLIP), PLATFORM);

        assertThat(loan.getPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.CLEARED);
        verifyNoInteractions(payslipReviewService);
    }

    @Test
    @DisplayName("the access log lists every view and upload newest first; an unknown loan is a 404")
    void accessLog() {
        LoanDocumentAccess view = LoanDocumentAccess.builder().id(2L).loanId(42L).documentId(10L)
                .documentType(DocumentType.PAYSLIP).version(1).action(DocumentAccessAction.VIEW)
                .performedBy("credit.manager").performedAt(LocalDateTime.of(2026, 9, 2, 10, 0)).build();
        when(loanDocumentAccessRepository.findByLoanIdOrderByIdDesc(42L)).thenReturn(List.of(view));

        assertThat(service.accessLog(42L)).containsExactly(new DocumentAccessResponse(2L, DocumentType.PAYSLIP, 1,
                DocumentAccessAction.VIEW, "credit.manager", LocalDateTime.of(2026, 9, 2, 10, 0)));
        assertThatThrownBy(() -> service.accessLog(7L))
                .isInstanceOf(NotFoundException.class).hasMessage("Loan 7 not found");
    }
}
