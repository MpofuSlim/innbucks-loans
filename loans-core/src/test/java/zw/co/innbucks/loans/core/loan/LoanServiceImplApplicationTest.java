package zw.co.innbucks.loans.core.loan;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.channel.ChannelRepository;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.document.DocumentAccessAction;
import zw.co.innbucks.loans.core.document.DocumentInspector;
import zw.co.innbucks.loans.core.document.DocumentOrigin;
import zw.co.innbucks.loans.core.document.DocumentRejectedException;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.document.DocumentUploadProperties;
import zw.co.innbucks.loans.core.document.LoanDocument;
import zw.co.innbucks.loans.core.document.LoanDocumentAccess;
import zw.co.innbucks.loans.core.document.LoanDocumentAccessRepository;
import zw.co.innbucks.loans.core.document.LoanDocumentRepository;
import zw.co.innbucks.loans.core.document.LoanDocumentService;
import zw.co.innbucks.loans.core.document.TestDocuments;
import zw.co.innbucks.loans.core.exception.IncompleteApplicationException;
import zw.co.innbucks.loans.core.files.DecodedFile;
import zw.co.innbucks.loans.core.files.FileSignatureValidator;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplate;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.instrument.SignedInstrumentService;
import zw.co.innbucks.loans.core.instrument.SigningContext;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.parameter.ParameterService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.turnaround.CreditTurnarounds;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static zw.co.innbucks.loans.core.loan.LoanParameterNames.*;

/**
 * {@link LoanServiceImpl#requestLoan} is also the entry point for bulk upload,
 * which never passes through the controller's {@code @Validated}. These pin that
 * the service refuses an incomplete application itself — before anything is
 * saved — and that {@code lineOfBusiness} now reaches the loan (it had no
 * request field, so InnBucks never received a business line).
 */
class LoanServiceImplApplicationTest {

    /** Where the application is signed, as the web layer reads it off the request. */
    private static final SigningContext SIGNING = new SigningContext("device-7f3a", "196.4.80.12", null,
            "InnBucksPortal/2.4", "pwd", null);

    private ValidatorFactory validatorFactory;
    private LoanRepository loanRepository;
    private LoanDocumentRepository loanDocumentRepository;
    private LoanDocumentAccessRepository loanDocumentAccessRepository;
    private PayslipReviewService payslipReviewService;
    private SignedInstrumentService signedInstrumentService;
    private LoanNotificationService loanNotificationService;
    private LoanServiceImpl service;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        loanRepository = mock(LoanRepository.class);
        ParameterService parameters = mock(ParameterService.class);
        when(parameters.getParameterValues(any(String[].class))).thenReturn(Map.of(
                COMMISSION_RATE, "10", ADMIN_FEE_RATE, "5", MONTHLY_INTEREST_RATE, "5",
                MINIMUM_LOAN_AMOUNT, "50", MAXIMUM_LOAN_AMOUNT, "5000",
                MINIMUM_LOAN_TENOR, "1", MAXIMUM_LOAN_TENOR, "24"));

        Merchant merchant = Merchant.builder().commissionStructure(CommissionStructure.MERCHANT_DEFINED)
                .commissionGroup(CommissionGroup.builder()
                        .agentCommission(BigDecimal.ZERO).providerCommission(BigDecimal.ZERO).build())
                .build();
        User agent = new User();
        agent.setUsername("agent.jane");
        agent.setMerchant(merchant);
        AuthService auth = mock(AuthService.class);
        when(auth.getLoggedInUser()).thenReturn(agent);

        payslipReviewService = mock(PayslipReviewService.class);
        signedInstrumentService = mock(SignedInstrumentService.class);
        loanNotificationService = mock(LoanNotificationService.class);
        loanDocumentRepository = mock(LoanDocumentRepository.class);
        when(loanDocumentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        loanDocumentAccessRepository = mock(LoanDocumentAccessRepository.class);
        PayslipFraudDetector payslipFraudDetector = new PayslipFraudDetector(loanDocumentRepository);
        LoanDocumentService loanDocumentService = new LoanDocumentService(loanDocumentRepository,
                loanDocumentAccessRepository, loanRepository,
                new DocumentInspector(new FileSignatureValidator(), new DocumentUploadProperties()), auth, payslipFraudDetector,
                payslipReviewService);
        service = new LoanServiceImpl(loanRepository, parameters, mock(LoanMapper.class), auth,
                mock(MerchantRepository.class), mock(ChannelRepository.class), validatorFactory.getValidator(),
                new MarketTimeZone("ZW"), loanDocumentService, payslipFraudDetector, payslipReviewService,
                signedInstrumentService, loanNotificationService, mock(CreditTurnarounds.class));
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("an incomplete application is refused before anything is saved")
    void incompleteApplicationIsRefusedUpFront() {
        LoanApplicationRequest incomplete = LoanApplicationRequestValidationTest.completeApplication();
        incomplete.setAddress(null);
        incomplete.setNextOfKin(null);

        assertThatThrownBy(() -> service.requestLoan(incomplete, SIGNING))
                .isInstanceOf(IllegalArgumentException.class)
                // Every missing field in one message: full path, sorted, "; "-joined.
                .hasMessage("address: Address is required; nextOfKin: Next of kin is required");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("an application missing what signing needs is refused before the applicant is locked or anything saved")
    void unsignedApplicationIsRefusedUpFront() {
        when(signedInstrumentService.requireAccepted(any(), any(), eq(SIGNING))).thenThrow(
                new IncompleteApplicationException(Map.of("loanAgreementVersion",
                        "The applicant must accept the loan agreement: version 3 is in force")));

        assertThatThrownBy(() -> service.requestLoan(LoanApplicationRequestValidationTest.completeApplication(), SIGNING))
                .isInstanceOf(IncompleteApplicationException.class);
        verify(loanRepository, never()).lockApplicant(any());
        verify(loanRepository, never()).save(any());
        verify(signedInstrumentService, never()).sign(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("the application is signed after the loan and its documents are saved, with the request's evidence")
    void signedAfterTheLoanIsSaved() {
        InstrumentTemplate agreement = InstrumentTemplate.builder().instrumentType(InstrumentType.LOAN_AGREEMENT)
                .version(3).title("SSB Loan Agreement").body("{{applicantName}}").build();
        when(signedInstrumentService.requireAccepted(any(), any(), eq(SIGNING))).thenReturn(List.of(agreement));
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setSignature(TestDocuments.base64(TestDocuments.encode(TestDocuments.signature(400, 160, false), "png")));

        service.requestLoan(request, SIGNING);

        InOrder order = inOrder(loanRepository, loanDocumentRepository, signedInstrumentService);
        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        order.verify(loanRepository).save(saved.capture());
        order.verify(loanDocumentRepository).save(any());
        ArgumentCaptor<Map<DocumentType, DecodedFile>> documents = ArgumentCaptor.captor();
        order.verify(signedInstrumentService).sign(eq(saved.getValue()), eq(List.of(agreement)), documents.capture(),
                eq(SIGNING), eq("agent.jane"));
        assertThat(documents.getValue()).containsKey(DocumentType.SIGNATURE);
    }

    @Test
    @DisplayName("dependants and children are stored as given (children used to be stored as the dependants count)")
    void dependantsAndChildrenAreStoredSeparately() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setNumberOfDependants(3);
        request.setNumberOfChildren(2);

        service.requestLoan(request, SIGNING);

        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        assertThat(saved.getValue().getNumberOfDependencies()).isEqualTo(3);
        assertThat(saved.getValue().getNumberOfChildren()).isEqualTo(2);
    }

    @Test
    @DisplayName("with no wallet number the loan pays the mobile number; a given one is stored normalised")
    void walletNumberDefaultsToTheMobile() {
        service.requestLoan(LoanApplicationRequestValidationTest.completeApplication(), SIGNING);
        LoanApplicationRequest withWallet = LoanApplicationRequestValidationTest.completeApplication();
        withWallet.setEcNumber("7654321B");
        withWallet.setNationalIdNumber("63-7654321B63");
        withWallet.setWalletNumber("0712345678");
        service.requestLoan(withWallet, SIGNING);

        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getWalletNumber()).isEqualTo("263772123123");
        assertThat(saved.getAllValues().get(1).getWalletNumber()).isEqualTo("263712345678");
        assertThat(saved.getAllValues().get(1).getMobileNumber()).isEqualTo("263772123123");
        assertThat(saved.getAllValues().get(1).payoutWalletNumber()).isEqualTo("263712345678");
    }

    @Test
    @DisplayName("the employment details and payslip deductions are stored as captured, deductions in order")
    void employmentAndPayslipAreStored() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setPayslipDeductions(List.of(
                new PayslipDeduction("  ZIMRA PAYE ", new BigDecimal("210.00")),
                new PayslipDeduction("CBZ personal loan", new BigDecimal("150.00"))));

        service.requestLoan(request, SIGNING);

        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        Loan loan = saved.getValue();
        assertThat(loan.getEmploymentDetail().getMinistry()).isEqualTo("Ministry of Health and Child Care");
        assertThat(loan.getEmploymentDetail().getStation()).isEqualTo("Mutare Provincial Hospital");
        assertThat(loan.getEmploymentDetail().getGrade()).isEqualTo("D2");
        assertThat(loan.getEmploymentDetail().getContractType()).isEqualTo(ContractType.PERMANENT);
        assertThat(loan.getEmploymentDetail().getNetSalary()).isEqualByComparingTo("1100.00");
        assertThat(loan.getPayslipDeductions()).containsExactly(
                new PayslipDeduction("ZIMRA PAYE", new BigDecimal("210.00")),
                new PayslipDeduction("CBZ personal loan", new BigDecimal("150.00")));
    }

    @Test
    @DisplayName("an application with no payslip deductions stores an empty list, not null")
    void noDeductionsIsAnEmptyList() {
        service.requestLoan(LoanApplicationRequestValidationTest.completeApplication(), SIGNING);

        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        assertThat(saved.getValue().getPayslipDeductions()).isEmpty();
    }

    @Test
    @DisplayName("a net salary above the gross is refused before anything is saved")
    void netAboveGrossIsRefused() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.getEmploymentDetail().setNetSalary(new BigDecimal("1500.01"));

        assertThatThrownBy(() -> service.requestLoan(request, SIGNING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Net salary cannot exceed gross salary");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("the quoted interest is stored with the loan (the loan view shows it; it used to be left empty)")
    void interestAmountIsStored() {
        service.requestLoan(LoanApplicationRequestValidationTest.completeApplication(), SIGNING);

        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        assertThat(saved.getValue().getInterestAmount()).isPositive();
    }

    @Test
    @DisplayName("a complete application is saved with its line of business")
    void lineOfBusinessReachesTheLoan() {
        LoanApplicationResponse response = service.requestLoan(LoanApplicationRequestValidationTest.completeApplication(), SIGNING);

        assertThat(response.ssbApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        assertThat(saved.getValue().getLineOfBusiness()).isEqualTo(LineOfBusiness.SERVICES);
        assertThat(saved.getValue().getLoanPurpose()).isEqualTo(LoanPurpose.HOME_IMPROVEMENT);
    }

    @Test
    @DisplayName("a single application's documents are checked like a bulk row's: an executable is refused, nothing saved")
    void singleApplicationDocumentsAreChecked() {
        LoanApplicationRequest withExecutable = LoanApplicationRequestValidationTest.completeApplication();
        withExecutable.setPayslipPicture(java.util.Base64.getEncoder().encodeToString("MZ\u0090\u0000 payload".getBytes()));

        assertThatThrownBy(() -> service.requestLoan(withExecutable, SIGNING))
                .isInstanceOf(DocumentRejectedException.class)
                .hasMessage("The payslip was refused: it is a program, not a document or photo. Please upload a PDF,"
                        + " PNG, JPEG or GIF file.");
        verify(loanRepository, never()).save(any());

        LoanApplicationRequest withUnknown = LoanApplicationRequestValidationTest.completeApplication();
        withUnknown.setNationalIdPicture("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString("not an image".getBytes()));
        assertThatThrownBy(() -> service.requestLoan(withUnknown, SIGNING))
                .hasMessage("The national ID must be a PDF, PNG, JPEG or GIF file.");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("a real PDF and a PNG data-URL pass the check and the application is saved")
    void recognisedDocumentsPass() {
        LoanApplicationRequest withDocuments = LoanApplicationRequestValidationTest.completeApplication();
        withDocuments.setPayslipPicture(TestDocuments.base64(TestDocuments.pdf(1)));
        withDocuments.setNationalIdPicture("data:image/png;base64,"
                + TestDocuments.base64(TestDocuments.encode(TestDocuments.page(1200, 800), "png")));

        service.requestLoan(withDocuments, SIGNING);

        verify(loanRepository).save(any());
    }

    @Test
    @DisplayName("each document is kept as version 1, uploaded by the originator, with the upload logged (FR-SSB-009)")
    void applicationDocumentsAreStoredAsVersionOne() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        byte[] pdf = TestDocuments.pdf(1);
        byte[] png = TestDocuments.encode(TestDocuments.signature(400, 150, true), "png");
        request.setPayslipPicture(java.util.Base64.getEncoder().encodeToString(pdf));
        request.setSignature("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png));
        Witness witness = new Witness();
        witness.setFullName("Tendai Moyo");
        witness.setSignature(java.util.Base64.getEncoder().encodeToString(png));
        request.setWitness(witness);

        service.requestLoan(request, SIGNING);

        ArgumentCaptor<LoanDocument> stored = ArgumentCaptor.forClass(LoanDocument.class);
        verify(loanDocumentRepository, times(3)).save(stored.capture());
        assertThat(stored.getAllValues())
                .extracting(LoanDocument::getDocumentType, LoanDocument::getVersion, LoanDocument::getOrigin,
                        LoanDocument::getContentType, LoanDocument::getUploadedBy)
                .containsExactly(
                        tuple(DocumentType.PAYSLIP, 1, DocumentOrigin.APPLICATION, "application/pdf", "agent.jane"),
                        tuple(DocumentType.SIGNATURE, 1, DocumentOrigin.APPLICATION, "image/png", "agent.jane"),
                        tuple(DocumentType.WITNESS_SIGNATURE, 1, DocumentOrigin.APPLICATION, "image/png", "agent.jane"));
        assertThat(stored.getAllValues().get(0).getContent()).isEqualTo(pdf);
        assertThat(stored.getAllValues().get(0).getSha256()).isEqualTo(AuditService.sha256Hex(pdf));
        ArgumentCaptor<LoanDocumentAccess> logged = ArgumentCaptor.forClass(LoanDocumentAccess.class);
        verify(loanDocumentAccessRepository, times(3)).save(logged.capture());
        assertThat(logged.getAllValues()).extracting(LoanDocumentAccess::getAction, LoanDocumentAccess::getPerformedBy)
                .containsOnly(tuple(DocumentAccessAction.UPLOAD, "agent.jane"));
    }

    @Test
    @DisplayName("a signature that is not base64 is refused before anything is saved")
    void undecodableSignatureIsRefused() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setSignature("signed: R. Chikwanha");

        assertThatThrownBy(() -> service.requestLoan(request, SIGNING))
                .isInstanceOf(DocumentRejectedException.class)
                .hasMessage("The signature could not be read: the upload was damaged. Please upload it again.");
        verify(loanRepository, never()).save(any());
        verify(loanDocumentRepository, never()).save(any());
    }

    // ── Payslip fraud controls (FR-SSB-007) ───────────────────────────────────

    private static final byte[] PAYSLIP_PDF = TestDocuments.pdf(1);
    private static final String PAYSLIP = TestDocuments.base64(PAYSLIP_PDF);

    private Loan applyWithPayslip(LoanApplicationRequest request) {
        request.setPayslipPicture(PAYSLIP);
        service.requestLoan(request, SIGNING);
        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        return saved.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<PayslipFraudDetector.Finding> held(Loan loan) {
        ArgumentCaptor<List<PayslipFraudDetector.Finding>> findings = ArgumentCaptor.forClass(List.class);
        verify(payslipReviewService).hold(eq(loan), findings.capture());
        return findings.getValue();
    }

    @Test
    @DisplayName("a payslip already on file under another identity holds the application for review")
    void payslipOfAnotherApplicantIsHeld() {
        when(loanDocumentRepository.findPayslipMatches(any(), any(), any()))
                .thenReturn(List.of(new PayslipMatch(17L, "7654321B", "637654321B42")));

        Loan loan = applyWithPayslip(LoanApplicationRequestValidationTest.completeApplication());

        assertThat(loan.getPayslipSha256()).isEqualTo(AuditService.sha256Hex(PAYSLIP_PDF));
        assertThat(loan.getPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.PENDING);
        assertThat(held(loan)).containsExactly(new PayslipFraudDetector.Finding(
                PayslipFraudReason.PAYSLIP_REUSED_BY_ANOTHER_APPLICANT, 17L, "Same payslip file as loan 000000017"));
        // The two applications are looked up under one lock, so neither passes the other unseen.
        verify(loanRepository).lockApplicant("loan-application:payslip:" + loan.getPayslipSha256());
    }

    @Test
    @DisplayName("the same payslip from the same applicant is held too; a shared EC number alone is not the same person")
    void payslipOfTheSameApplicantIsHeld() {
        when(loanDocumentRepository.findPayslipMatches(any(), any(), any())).thenReturn(List.of(
                new PayslipMatch(9L, "1234567a", "63-1234567-A-63"),
                new PayslipMatch(8L, "1234567A", "639999999Z99")));

        Loan loan = applyWithPayslip(LoanApplicationRequestValidationTest.completeApplication());

        assertThat(held(loan))
                .extracting(PayslipFraudDetector.Finding::reason, PayslipFraudDetector.Finding::matchedLoanId)
                .containsExactly(
                        tuple(PayslipFraudReason.PAYSLIP_REUSED_BY_SAME_APPLICANT, 9L),
                        tuple(PayslipFraudReason.PAYSLIP_REUSED_BY_ANOTHER_APPLICANT, 8L));
    }

    @Test
    @DisplayName("deductions adding up to more than gross less net hold the application")
    void deductionsBeyondThePayslipAreHeld() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        // Gross 1500.00 less net 1100.00 leaves 400.00; these add up to 450.00.
        request.setPayslipDeductions(List.of(
                new PayslipDeduction("ZIMRA PAYE", new BigDecimal("300.00")),
                new PayslipDeduction("PSMAS medical aid", new BigDecimal("150.00"))));

        Loan loan = applyWithPayslip(request);

        assertThat(loan.getPayslipReviewStatus()).isEqualTo(PayslipReviewStatus.PENDING);
        assertThat(held(loan)).containsExactly(new PayslipFraudDetector.Finding(
                PayslipFraudReason.DEDUCTIONS_EXCEED_GROSS_LESS_NET, null,
                "Deductions total 450.00 but gross less net is 400.00"));
    }

    @Test
    @DisplayName("deductions up to gross less net are ordinary: not every line need be captured")
    void deductionsWithinThePayslipPass() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setPayslipDeductions(List.of(new PayslipDeduction("ZIMRA PAYE", new BigDecimal("400.00"))));

        Loan loan = applyWithPayslip(request);

        assertThat(loan.getPayslipReviewStatus()).isNull();
        verifyNoInteractions(payslipReviewService);
    }

    @Test
    @DisplayName("an application with nothing suspect is fingerprinted and not held")
    void cleanApplicationIsNotHeld() {
        Loan loan = applyWithPayslip(LoanApplicationRequestValidationTest.completeApplication());

        assertThat(loan.getPayslipSha256()).hasSize(64);
        assertThat(loan.getPayslipReviewStatus()).isNull();
        verifyNoInteractions(payslipReviewService);
    }

    @Test
    @DisplayName("an application with no payslip takes no payslip lock and matches nothing")
    void noPayslipNoLock() {
        service.requestLoan(LoanApplicationRequestValidationTest.completeApplication(), SIGNING);

        verify(loanRepository, never()).lockApplicant(startsWith("loan-application:payslip:"));
        verify(loanDocumentRepository, never()).findPayslipMatches(any(), any(), any());
    }
}
