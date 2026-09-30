package zw.co.innbucks.loans.core.loan;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.channel.ChannelRepository;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.document.LoanDocumentService;
import zw.co.innbucks.loans.core.instrument.SignedInstrumentService;
import zw.co.innbucks.loans.core.instrument.SigningContext;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.core.parameter.ParameterService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.turnaround.CreditTurnarounds;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static zw.co.innbucks.loans.core.loan.LoanParameterNames.*;

/**
 * Every application is attributed to the officer or agent who originated it, whichever channel it came through
 * (FR-SSB-017). A channel used to replace the caller with its own system account: the officer vanished from the
 * loan, their commission was priced for someone else's merchant, they could no longer see the loan they had just
 * captured, and a credit officer could approve an application they had captured themselves.
 */
class LoanServiceImplAttributionTest {

    private static final SigningContext SIGNING = new SigningContext("device-7f3a", "196.4.80.12", null,
            "InnBucksPortal/2.4", "pwd", null);

    private ValidatorFactory validatorFactory;
    private LoanRepository loanRepository;
    private ChannelRepository channelRepository;
    private SignedInstrumentService signedInstrumentService;
    private LoanServiceImpl service;
    private User officer;
    private Channel superApp;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        loanRepository = mock(LoanRepository.class);
        channelRepository = mock(ChannelRepository.class);
        signedInstrumentService = mock(SignedInstrumentService.class);
        ParameterService parameters = mock(ParameterService.class);
        when(parameters.getParameterValues(any(String[].class))).thenReturn(Map.of(
                COMMISSION_RATE, "10", ADMIN_FEE_RATE, "5", MONTHLY_INTEREST_RATE, "5",
                MINIMUM_LOAN_AMOUNT, "50", MAXIMUM_LOAN_AMOUNT, "5000",
                MINIMUM_LOAN_TENOR, "1", MAXIMUM_LOAN_TENOR, "24"));

        officer = user(7L, "tmoyo", "Tendai", "Moyo", "harare-motors", 20);
        // The channel's own account: another merchant, and another commission share.
        superApp = Channel.builder().channelId("superapp").name("InnBucks SuperApp")
                .systemUser(user(1L, "superapp-service", null, null, "innbucks", 50)).build();
        when(channelRepository.findChannelByChannelId("superapp")).thenReturn(Optional.of(superApp));

        AuthService auth = mock(AuthService.class);
        when(auth.getLoggedInUser()).thenReturn(officer);
        LoanDocumentService documents = mock(LoanDocumentService.class);
        when(documents.decodeApplication(any())).thenReturn(Map.of());
        service = new LoanServiceImpl(loanRepository, parameters, mock(LoanMapper.class), auth,
                mock(MerchantRepository.class), channelRepository, validatorFactory.getValidator(),
                new MarketTimeZone("ZW"), documents, mock(PayslipFraudDetector.class),
                mock(PayslipReviewService.class), signedInstrumentService, mock(LoanNotificationService.class),
                mock(CreditTurnarounds.class));
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    /** A user whose merchant leaves commission to each agent's own group, of the given agent share. */
    private static User user(Long id, String username, String firstName, String lastName, String merchantCode,
                             int agentShare) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setMerchant(Merchant.builder().merchantCode(merchantCode)
                .commissionStructure(CommissionStructure.AGENT_DEFINED).build());
        user.setCommissionGroup(CommissionGroup.builder().percentage(true)
                .agentCommission(BigDecimal.valueOf(agentShare)).providerCommission(BigDecimal.valueOf(100 - agentShare))
                .build());
        return user;
    }

    private Loan saved() {
        ArgumentCaptor<Loan> loan = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(loan.capture());
        return loan.getValue();
    }

    @Test
    @DisplayName("captured in the portal: the officer originated it, and no channel is recorded")
    void portalApplicationIsTheOfficers() {
        service.requestLoan(LoanApplicationRequestValidationTest.completeApplication(), SIGNING);

        Loan loan = saved();
        assertThat(loan.getCreatedBy()).isEqualTo("tmoyo");
        assertThat(loan.getCreatedByUser()).isSameAs(officer);
        assertThat(loan.getMerchant().getMerchantCode()).isEqualTo("harare-motors");
        assertThat(loan.getChannel()).isNull();
        verifyNoInteractions(channelRepository);
    }

    @Test
    @DisplayName("through a channel: still the officer's, their merchant's and priced with their commission;"
            + " the channel is recorded as where it came from")
    void channelApplicationIsStillTheOfficers() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setChannelId("superapp");

        service.requestLoan(request, SIGNING);

        Loan loan = saved();
        assertThat(loan.getCreatedBy()).isEqualTo("tmoyo");
        assertThat(loan.getCreatedByUser()).isSameAs(officer);
        assertThat(loan.getMerchant().getMerchantCode()).isEqualTo("harare-motors");
        assertThat(loan.getChannel()).isSameAs(superApp);
        // Priced with the officer's 20% share of the commission, not the channel account's 50%.
        assertThat(loan.getAgentCommissionRate()).isEqualByComparingTo("20");
        // The documents and the signature are the officer's too.
        verify(signedInstrumentService).sign(eq(loan), any(), any(), eq(SIGNING), eq("tmoyo"));
    }

    @Test
    @DisplayName("the officer can read the loan they captured through a channel, and cannot credit-approve it")
    void theOfficerKeepsScopeAndSegregationOfDuties() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setChannelId("superapp");

        service.requestLoan(request, SIGNING);

        Loan loan = saved();
        // The originator read scope matches on the merchant and the capturing user.
        assertThat(loan.getMerchant().getMerchantCode()).isEqualTo("harare-motors");
        assertThat(loan.getCreatedByUser().getId()).isEqualTo(7L);
        assertThat(SegregationOfDuties.originated(loan, "tmoyo")).isTrue();
        assertThat(SegregationOfDuties.originated(loan, "superapp-service")).isFalse();
    }

    @Test
    @DisplayName("the channel's own account originates what it submits itself, exactly as before")
    void theChannelAccountOriginatesItsOwnSubmissions() {
        AuthService auth = mock(AuthService.class);
        when(auth.getLoggedInUser()).thenReturn(superApp.getSystemUser());
        LoanDocumentService documents = mock(LoanDocumentService.class);
        when(documents.decodeApplication(any())).thenReturn(Map.of());
        ParameterService parameters = mock(ParameterService.class);
        when(parameters.getParameterValues(any(String[].class))).thenReturn(Map.of(
                COMMISSION_RATE, "10", ADMIN_FEE_RATE, "5", MONTHLY_INTEREST_RATE, "5",
                MINIMUM_LOAN_AMOUNT, "50", MAXIMUM_LOAN_AMOUNT, "5000",
                MINIMUM_LOAN_TENOR, "1", MAXIMUM_LOAN_TENOR, "24"));
        LoanServiceImpl asChannel = new LoanServiceImpl(loanRepository, parameters, mock(LoanMapper.class), auth,
                mock(MerchantRepository.class), channelRepository, validatorFactory.getValidator(),
                new MarketTimeZone("ZW"), documents, mock(PayslipFraudDetector.class),
                mock(PayslipReviewService.class), signedInstrumentService, mock(LoanNotificationService.class),
                mock(CreditTurnarounds.class));
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setChannelId("superapp");

        asChannel.requestLoan(request, SIGNING);

        Loan loan = saved();
        assertThat(loan.getCreatedBy()).isEqualTo("superapp-service");
        assertThat(loan.getMerchant().getMerchantCode()).isEqualTo("innbucks");
        assertThat(loan.getChannel()).isSameAs(superApp);
    }

    @Test
    @DisplayName("a channelId naming no registered channel is refused before the applicant is locked or anything saved")
    void unknownChannelIsRefusedUpFront() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setChannelId("superap");

        assertThatThrownBy(() -> service.requestLoan(request, SIGNING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No channel is registered under that channelId");
        verify(loanRepository, never()).lockApplicant(anyString());
        verify(loanRepository, never()).save(any());
        verify(signedInstrumentService, never()).requireAccepted(any(), any(), any());
    }

    @Test
    @DisplayName("a blank channelId is no channel; a padded one is found by its trimmed id")
    void blankOrPaddedChannelId() {
        LoanApplicationRequest blank = LoanApplicationRequestValidationTest.completeApplication();
        blank.setChannelId("  ");
        service.requestLoan(blank, SIGNING);
        LoanApplicationRequest padded = LoanApplicationRequestValidationTest.completeApplication();
        padded.setEcNumber("7654321B");
        padded.setNationalIdNumber("63-7654321B63");
        padded.setChannelId(" superapp ");
        service.requestLoan(padded, SIGNING);

        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getChannel()).isNull();
        assertThat(saved.getAllValues().get(1).getChannel()).isSameAs(superApp);
        verify(channelRepository).findChannelByChannelId("superapp");
    }

    @Test
    @DisplayName("the preview prices the instruments for the officer too, and refuses an unknown channel the same way")
    void previewIsAttributedTheSameWay() {
        LoanApplicationRequest request = LoanApplicationRequestValidationTest.completeApplication();
        request.setChannelId("superapp");

        service.previewInstruments(request);

        ArgumentCaptor<Loan> previewed = ArgumentCaptor.forClass(Loan.class);
        verify(signedInstrumentService).preview(previewed.capture());
        assertThat(previewed.getValue().getCreatedBy()).isEqualTo("tmoyo");
        assertThat(previewed.getValue().getAgentCommissionRate()).isEqualByComparingTo("20");
        assertThat(previewed.getValue().getChannel()).isSameAs(superApp);

        request.setChannelId("superap");
        assertThatThrownBy(() -> service.previewInstruments(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No channel is registered under that channelId");
        verify(loanRepository, never()).save(any());
    }
}
