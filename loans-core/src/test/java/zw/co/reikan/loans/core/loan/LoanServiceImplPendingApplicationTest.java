package zw.co.reikan.loans.core.loan;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.jpa.repository.Query;
import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.channel.ChannelRepository;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionStructure;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.parameter.ParameterService;
import zw.co.reikan.loans.core.user.User;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static zw.co.reikan.loans.core.loan.Constants.*;

/**
 * The duplicate-application check in {@link LoanServiceImpl#requestLoan}. It used
 * to let duplicates through four ways: a lower-case EC check letter never matched,
 * only {@code NEW} counted as pending (a loan lodged with Ndasenda a minute
 * earlier did not), the national ID was never checked, and two concurrent
 * submissions could both pass before either inserted. What counts as in flight is
 * pinned by {@link LoanStatusSnapshotTest}; these pin how the service applies it.
 */
class LoanServiceImplPendingApplicationTest {

    private static final String EC_NUMBER = "1234567A";
    // completeApplication()'s "63-1234567A63", normalised.
    private static final String NATIONAL_ID = "631234567A63";

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

    private static LoanRequest application(String ecNumber) {
        LoanRequest request = LoanRequestValidationTest.completeApplication();
        request.setEcnumber(ecNumber);
        return request;
    }

    private static LoanStatusSnapshot loan(LoanApprovalStatus approval, InternalApprovalStatus credit,
                                           LoanAccountStatus account, LoanDisbursementStatus disbursement) {
        return new LoanStatusSnapshot(42L, approval, credit, account, disbursement);
    }

    private static LoanStatusSnapshot lodgedWithNdasenda() {
        return loan(LoanApprovalStatus.PROCESSING, InternalApprovalStatus.PENDING,
                LoanAccountStatus.PENDING, LoanDisbursementStatus.PENDING);
    }

    private void assertRefusedAsPending(LoanResponse response) {
        assertThat(response.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.REJECTED);
        assertThat(response.getMessage()).isEqualTo("You have a pending loan application.");
        assertThat(response.getInternalReference()).isNull();
        verify(loanRepository, never()).save(any());
    }

    private void assertAccepted(LoanResponse response) {
        assertThat(response.getLoanApprovalStatus()).isEqualTo(LoanApprovalStatus.NEW);
        verify(loanRepository).save(any(Loan.class));
    }

    @Test
    @DisplayName("the EC number is stored upper-cased")
    void storedEcNumberIsUpperCased() {
        service.requestLoan(application("1234567a"));

        ArgumentCaptor<Loan> saved = ArgumentCaptor.forClass(Loan.class);
        verify(loanRepository).save(saved.capture());
        assertThat(saved.getValue().getEcNumber()).isEqualTo(EC_NUMBER);
    }

    @Test
    @DisplayName("a lower-case EC check letter is looked up upper-cased and matches")
    void lowerCaseEcNumberIsMatched() {
        when(loanRepository.findStatusesByEcNumber(EC_NUMBER)).thenReturn(List.of(lodgedWithNdasenda()));

        assertRefusedAsPending(service.requestLoan(application("1234567a")));
    }

    @Test
    @DisplayName("rows stored before normalisation are compared upper-cased in the query")
    void legacyRowsAreComparedUpperCased() throws NoSuchMethodException {
        // Rows saved before this fix keep the check letter as typed; the query,
        // not a data migration, is what lets "1234567a" on file match.
        assertThat(LoanRepository.class.getMethod("findStatusesByEcNumber", String.class)
                .getAnnotation(Query.class).value()).contains("upper(l.ecNumber) = :ecNumber");
        assertThat(LoanRepository.class.getMethod("findStatusesByNationalId", String.class)
                .getAnnotation(Query.class).value()).contains("upper(l.nationalIdNumber) = :nationalIdNumber");
    }

    @Test
    @DisplayName("an application already lodged with Ndasenda (PROCESSING) blocks another")
    void processingApplicationBlocks() {
        when(loanRepository.findStatusesByEcNumber(EC_NUMBER)).thenReturn(List.of(lodgedWithNdasenda()));

        assertRefusedAsPending(service.requestLoan(application(EC_NUMBER)));
    }

    @Test
    @DisplayName("an SSB-approved application awaiting credit blocks another")
    void ssbApprovedAwaitingCreditBlocks() {
        when(loanRepository.findStatusesByEcNumber(EC_NUMBER)).thenReturn(List.of(loan(
                LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING,
                LoanAccountStatus.PENDING, LoanDisbursementStatus.PENDING)));

        assertRefusedAsPending(service.requestLoan(application(EC_NUMBER)));
    }

    @Test
    @DisplayName("rejected, failed and disbursed applications do not block another")
    void concludedApplicationsDoNotBlock() {
        when(loanRepository.findStatusesByEcNumber(EC_NUMBER)).thenReturn(List.of(
                loan(LoanApprovalStatus.REJECTED, InternalApprovalStatus.PENDING,
                        LoanAccountStatus.PENDING, LoanDisbursementStatus.PENDING),
                loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.REJECTED,
                        LoanAccountStatus.PENDING, LoanDisbursementStatus.PENDING),
                loan(LoanApprovalStatus.FAILED, InternalApprovalStatus.PENDING,
                        LoanAccountStatus.PENDING, LoanDisbursementStatus.PENDING),
                loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED,
                        LoanAccountStatus.FAILED, LoanDisbursementStatus.FAILED),
                loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED,
                        LoanAccountStatus.CREATED, LoanDisbursementStatus.SUCCESS)));
        when(loanRepository.findStatusesByNationalId(NATIONAL_ID)).thenReturn(List.of(
                loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED,
                        LoanAccountStatus.CREATED, LoanDisbursementStatus.SUCCESS)));

        assertAccepted(service.requestLoan(application(EC_NUMBER)));
    }

    @Test
    @DisplayName("the same national ID under a different EC number blocks")
    void nationalIdMatchBlocks() {
        // Nothing in flight under the (mistyped) EC number on this application...
        when(loanRepository.findStatusesByEcNumber(EC_NUMBER)).thenReturn(List.of());
        // ...but the person's national ID has an application with Ndasenda.
        when(loanRepository.findStatusesByNationalId(NATIONAL_ID)).thenReturn(List.of(lodgedWithNdasenda()));

        assertRefusedAsPending(service.requestLoan(application(EC_NUMBER)));
    }

    @Test
    @DisplayName("the applicant is locked before the pending check, and the check runs before the insert")
    void lockIsTakenBeforeTheCheck() {
        service.requestLoan(application("1234567a"));

        InOrder order = inOrder(loanRepository);
        order.verify(loanRepository).lockApplicant("loan-application:ec:" + EC_NUMBER);
        order.verify(loanRepository).lockApplicant("loan-application:nid:" + NATIONAL_ID);
        order.verify(loanRepository).findStatusesByEcNumber(EC_NUMBER);
        order.verify(loanRepository).findStatusesByNationalId(NATIONAL_ID);
        order.verify(loanRepository).save(any(Loan.class));
    }

    @Test
    @DisplayName("a national ID that normalises to nothing is neither locked nor matched")
    void blankNationalIdIsNotMatched() {
        LoanRequest request = application(EC_NUMBER);
        request.setNationalId("--");

        assertAccepted(service.requestLoan(request));
        verify(loanRepository).lockApplicant("loan-application:ec:" + EC_NUMBER);
        verify(loanRepository, never()).lockApplicant(startsWith("loan-application:nid:"));
        verify(loanRepository, never()).findStatusesByNationalId(any());
    }
}
