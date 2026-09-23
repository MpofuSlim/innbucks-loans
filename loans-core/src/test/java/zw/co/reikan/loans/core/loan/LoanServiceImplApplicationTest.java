package zw.co.reikan.loans.core.loan;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.channel.ChannelRepository;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionStructure;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.parameter.ParameterService;
import zw.co.reikan.loans.core.user.User;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static zw.co.reikan.loans.core.loan.Constants.*;

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
                COMMISSION_RATE, "10", ADMI_FEE_RATE, "5", MONTHLY_INTEREST_RATE, "5",
                AGENT_COMMISSION_RATE, "0", MINIMUM_LOAN_AMOUNT, "50", MAXIMUM_LOAN_AMOUNT, "5000",
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
                mock(MerchantRepository.class), mock(ChannelRepository.class), validatorFactory.getValidator());
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("an incomplete application (e.g. a bulk row) is refused before anything is saved")
    void incompleteApplicationIsRefusedUpFront() {
        LoanRequest incomplete = LoanRequestValidationTest.completeApplication();
        incomplete.setAddress(null);
        incomplete.setNextOfKin(null);

        assertThatThrownBy(() -> service.requestLoan(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                // Same shape as the web layer's 400: full path, sorted, "; "-joined.
                .hasMessage("address: Address is required; nextOfKin: Next of kin is required");
        verify(loanRepository, never()).save(any());
    }

    @Test
    @DisplayName("a complete application is saved with its line of business")
    void lineOfBusinessReachesTheLoan() {
        LoanResponse response = service.requestLoan(LoanRequestValidationTest.completeApplication());

        assertThat(response.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        assertThat(saved.getValue().getLineOfBusiness()).isEqualTo(LineOfBusiness.SERVICES);
        assertThat(saved.getValue().getLoanPurpose()).isEqualTo(LoanPurpose.HOME_IMPROVEMENT);
    }
}
