package zw.co.innbucks.loans.core.loan;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.channel.ChannelRepository;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.files.FileSignatureValidator;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.parameter.ParameterService;
import zw.co.innbucks.loans.core.user.User;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        service = new LoanServiceImpl(loanRepository, parameters, mock(LoanMapper.class), auth,
                mock(MerchantRepository.class), mock(ChannelRepository.class), validatorFactory.getValidator(), new MarketTimeZone("ZW"), new FileSignatureValidator());
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
}
