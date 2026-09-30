package zw.co.innbucks.loans.core.loan;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.channel.ChannelRepository;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.document.DocumentAccessAction;
import zw.co.innbucks.loans.core.document.DocumentOrigin;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.document.LoanDocument;
import zw.co.innbucks.loans.core.document.LoanDocumentAccess;
import zw.co.innbucks.loans.core.document.LoanDocumentAccessRepository;
import zw.co.innbucks.loans.core.document.LoanDocumentRepository;
import zw.co.innbucks.loans.core.document.LoanDocumentService;
import zw.co.innbucks.loans.core.files.FileSignatureValidator;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.parameter.ParameterService;
import zw.co.innbucks.loans.core.user.User;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
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

    private ValidatorFactory validatorFactory;
    private LoanRepository loanRepository;
    private LoanDocumentRepository loanDocumentRepository;
    private LoanDocumentAccessRepository loanDocumentAccessRepository;
    private PayslipReviewService payslipReviewService;
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
        loanDocumentRepository = mock(LoanDocumentRepository.class);
        when(loanDocumentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        loanDocumentAccessRepository = mock(LoanDocumentAccessRepository.class);
        PayslipFraudDetector payslipFraudDetector = new PayslipFraudDetector(loanDocumentRepository);
        LoanDocumentService loanDocumentService = new LoanDocumentService(loanDocumentRepository,
                loanDocumentAccessRepository, loanRepository, new FileSignatureValidator(), auth, payslipFraudDetector,
                payslipReviewService);
        service = new LoanServiceImpl(loanRepository, parameters, mock(LoanMapper.class), auth,
                mock(MerchantRepository.class), mock(ChannelRepository.class), validatorFactory.getValidator(),
                new MarketTimeZone("ZW"), loanDocumentService, payslipFraudDetector, payslipReviewService);
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

        assertThatThrownBy(() -> service.requestLoan(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                // Every missing field in one message: full path, sorted, "; "-joined.
                .hasMessage("address: Address is required; nextOfKin: Next of kin is required");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("dependants and children are stored as given (children used to be stored as the dependants count)")
    void dependantsAndChildrenAreStoredSeparately() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setNumberOfDependants(3);
        request.setNumberOfChildren(2);

        service.requestLoan(request);

        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        assertThat(saved.getValue().getNumberOfDependencies()).isEqualTo(3);
        assertThat(saved.getValue().getNumberOfChildren()).isEqualTo(2);
    }

    @Test
    @DisplayName("with no wallet number the loan pays the mobile number; a given one is stored normalised")
    void walletNumberDefaultsToTheMobile() {
        service.requestLoan(LoanApplicationRequestValidationTest.completeApplication());
        LoanApplicationRequest withWallet = LoanApplicationRequestValidationTest.completeApplication();
        withWallet.setEcNumber("7654321B");
        withWallet.setNationalIdNumber("63-7654321B63");
        withWallet.setWalletNumber("0712345678");
        service.requestLoan(withWallet);

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

        service.requestLoan(request);

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
        service.requestLoan(LoanApplicationRequestValidationTest.completeApplication());

        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        assertThat(saved.getValue().getPayslipDeductions()).isEmpty();
    }

    @Test
    @DisplayName("a net salary above the gross is refused before anything is saved")
    void netAboveGrossIsRefused() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.getEmploymentDetail().setNetSalary(new BigDecimal("1500.01"));

        assertThatThrownBy(() -> service.requestLoan(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Net salary cannot exceed gross salary");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("the quoted interest is stored with the loan (the loan view shows it; it used to be left empty)")
    void interestAmountIsStored() {
        service.requestLoan(LoanApplicationRequestValidationTest.completeApplication());

        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        assertThat(saved.getValue().getInterestAmount()).isPositive();
    }

    @Test
    @DisplayName("a complete application is saved with its line of business")
    void lineOfBusinessReachesTheLoan() {
        LoanApplicationResponse response = service.requestLoan(LoanApplicationRequestValidationTest.completeApplication());

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

        assertThatThrownBy(() -> service.requestLoan(withExecutable))
                .isInstanceOf(FileSignatureValidator.UnsafeFileException.class)
                .hasMessageContaining("payslipPicture contains an executable");
        verify(loanRepository, never()).save(any());

        LoanApplicationRequest withUnknown = LoanApplicationRequestValidationTest.completeApplication();
        withUnknown.setNationalIdPicture("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString("not an image".getBytes()));
        assertThatThrownBy(() -> service.requestLoan(withUnknown))
                .hasMessageContaining("nationalIdPicture is not a recognised document type");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("a real PDF and a PNG data-URL pass the check and the application is saved")
    void recognisedDocumentsPass() {
        LoanApplicationRequest withDocuments = LoanApplicationRequestValidationTest.completeApplication();
        withDocuments.setPayslipPicture(java.util.Base64.getEncoder().encodeToString("%PDF-1.7 payslip".getBytes()));
        withDocuments.setNationalIdPicture("data:image/png;base64," + java.util.Base64.getEncoder()
                .encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));

        service.requestLoan(withDocuments);

        verify(loanRepository).save(any());
    }

    @Test
    @DisplayName("each document is kept as version 1, uploaded by the originator, with the upload logged (FR-SSB-009)")
    void applicationDocumentsAreStoredAsVersionOne() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        byte[] pdf = "%PDF-1.7 payslip".getBytes();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        request.setPayslipPicture(java.util.Base64.getEncoder().encodeToString(pdf));
        request.setSignature("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png));
        Witness witness = new Witness();
        witness.setFullName("Tendai Moyo");
        witness.setSignature(java.util.Base64.getEncoder().encodeToString(png));
        request.setWitness(witness);

        service.requestLoan(request);

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

        assertThatThrownBy(() -> service.requestLoan(request))
                .isInstanceOf(FileSignatureValidator.UnsafeFileException.class)
                .hasMessage("signature is not valid base64 content");
        verify(loanRepository, never()).save(any());
        verify(loanDocumentRepository, never()).save(any());
    }

    // ── Payslip fraud controls (FR-SSB-007) ───────────────────────────────────

    private static final String PAYSLIP = java.util.Base64.getEncoder().encodeToString("%PDF-1.7 payslip".getBytes());

    private Loan applyWithPayslip(LoanApplicationRequest request) {
        request.setPayslipPicture(PAYSLIP);
        service.requestLoan(request);
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

        assertThat(loan.getPayslipSha256()).isEqualTo(AuditService.sha256Hex("%PDF-1.7 payslip".getBytes()));
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
        service.requestLoan(LoanApplicationRequestValidationTest.completeApplication());

        verify(loanRepository, never()).lockApplicant(startsWith("loan-application:payslip:"));
        verify(loanDocumentRepository, never()).findPayslipMatches(any(), any(), any());
    }
}
