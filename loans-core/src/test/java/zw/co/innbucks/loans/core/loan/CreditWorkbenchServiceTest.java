package zw.co.innbucks.loans.core.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.document.DocumentOrigin;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.document.LoanDocumentSummary;
import zw.co.innbucks.loans.core.employment.EmploymentEventService;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaround;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import zw.co.innbucks.loans.core.workflow.CheckpointGate;
import zw.co.innbucks.loans.core.workflow.HoldPoint;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** The credit workbench gathers what an officer needs to decide one application (FR-SSB-015 / FR-PBL-026). */
class CreditWorkbenchServiceTest {

    /** 10:00 on 30 September in Harare. */
    private static final Instant NOW = Instant.parse("2026-09-30T08:00:00Z");

    private LoanRepository loanRepository;
    private LoanService loanService;
    private CreditDecisionService creditDecisionService;
    private EmploymentEventService employmentEventService;
    private PayslipFraudFlagRepository payslipFraudFlagRepository;
    private CheckpointGate checkpointGate;
    private CreditWorkbenchService service;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        loanService = mock(LoanService.class);
        creditDecisionService = mock(CreditDecisionService.class);
        employmentEventService = mock(EmploymentEventService.class);
        payslipFraudFlagRepository = mock(PayslipFraudFlagRepository.class);
        checkpointGate = mock(CheckpointGate.class);
        service = new CreditWorkbenchService(loanRepository, loanService, creditDecisionService, employmentEventService,
                payslipFraudFlagRepository, new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC)), checkpointGate);
    }

    /** Loan 42 as in the examples: a teacher earning 850 gross, 620 net, deducting 208.96 a month. */
    private static Loan loan42() {
        Loan loan = new Loan();
        loan.setId(42L);
        loan.setEcNumber("1234567A");
        loan.setNationalIdNumber("631234567A42");
        EmploymentDetail employment = new EmploymentDetail();
        employment.setGrossSalary(new BigDecimal("850.00"));
        employment.setNetSalary(new BigDecimal("620.00"));
        loan.setEmploymentDetail(employment);
        loan.setPayslipDeductions(List.of(deduction("ZIMRA PAYE", "142.50"), deduction("PSMAS medical aid", "45.00"),
                deduction("APEX pension", "42.50")));
        loan.setMonthlyInstallment(new BigDecimal("202.69"));
        loan.setGrossedMonthlyDeduction(new BigDecimal("208.96"));
        loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        loan.setInternalApprovalStatus(InternalApprovalStatus.PENDING);
        return loan;
    }

    private static PayslipDeduction deduction(String beneficiary, String amount) {
        PayslipDeduction deduction = new PayslipDeduction();
        deduction.setBeneficiary(beneficiary);
        deduction.setAmount(new BigDecimal(amount));
        return deduction;
    }

    private static Loan other(long id, LoanApprovalStatus ssb, InternalApprovalStatus credit, String principal,
                              String deduction) {
        Loan loan = new Loan();
        loan.setId(id);
        loan.setLoanApprovalStatus(ssb);
        loan.setInternalApprovalStatus(credit);
        loan.setPrincipal(new BigDecimal(principal));
        loan.setGrossedMonthlyDeduction(new BigDecimal(deduction));
        loan.setTenor(3);
        return loan;
    }

    private LoanResponse viewed(Loan loan) {
        LoanResponse view = new LoanResponse();
        view.setId(loan.getId());
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(loanService.getLoan(loan.getId(), LoanReadScope.platform())).thenReturn(view);
        return view;
    }

    private static List<String> codes(CreditWorkbenchResponse workbench) {
        return workbench.flags().stream().map(CreditWorkbenchResponse.Flag::code).toList();
    }

    @Test
    @DisplayName("affordability sets the deduction SSB is instructed to take against the payslip, without a verdict")
    void affordability() {
        Loan loan = loan42();
        viewed(loan);

        CreditWorkbenchResponse.Affordability affordability = service.workbench(42L).affordability();

        assertThat(affordability.grossSalary()).isEqualByComparingTo("850.00");
        assertThat(affordability.netSalary()).isEqualByComparingTo("620.00");
        assertThat(affordability.payslipDeductions()).isEqualByComparingTo("230.00");
        assertThat(affordability.monthlyDeduction()).isEqualByComparingTo("208.96");
        assertThat(affordability.netAfterDeduction()).isEqualByComparingTo("411.04");
        assertThat(affordability.deductionToNetPercent()).isEqualByComparingTo("33.7");
        assertThat(affordability.outcome()).isEqualTo(CreditWorkbenchResponse.AffordabilityOutcome.NOT_ASSESSED);
        assertThat(affordability.note()).isEqualTo(CreditWorkbenchService.AFFORDABILITY_NOT_ASSESSED);
    }

    @Test
    @DisplayName("a loan with no grossed-up deduction is measured by its instalment; a zero net salary has no ratio")
    void affordabilityFallbacks() {
        Loan loan = loan42();
        loan.setGrossedMonthlyDeduction(null);
        loan.getEmploymentDetail().setNetSalary(BigDecimal.ZERO);
        loan.setPayslipDeductions(List.of());

        CreditWorkbenchResponse.Affordability affordability = CreditWorkbenchService.affordabilityOf(loan);

        assertThat(affordability.monthlyDeduction()).isEqualByComparingTo("202.69");
        assertThat(affordability.netAfterDeduction()).isEqualByComparingTo("-202.69");
        assertThat(affordability.deductionToNetPercent()).isNull();
        assertThat(affordability.payslipDeductions()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("exposure finds the applicant's other loans by EC number and by national ID, once each, without this one")
    void exposureAcrossBothIdentifiers() {
        Loan loan = loan42();
        viewed(loan);
        Loan paidRunning = other(12L, LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED, "319.15", "125.38");
        paidRunning.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        paidRunning.setRepaymentEndDate(LocalDate.of(2026, 11, 30));
        Loan paidOff = other(8L, LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED, "212.77", "83.59");
        paidOff.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        paidOff.setRepaymentEndDate(LocalDate.of(2026, 6, 30));
        Loan declined = other(20L, LoanApprovalStatus.APPROVED, InternalApprovalStatus.REJECTED, "500.00", "196.43");
        Loan pending = other(30L, LoanApprovalStatus.PROCESSING, InternalApprovalStatus.PENDING, "100.00", "39.29");
        when(loanRepository.findByEcNumberOrderByIdAsc("1234567A")).thenReturn(List.of(paidOff, paidRunning, loan));
        when(loanRepository.findByNationalIdNumberOrderByIdAsc("631234567A42"))
                .thenReturn(List.of(paidRunning, declined, pending, loan));

        CreditWorkbenchResponse workbench = service.workbench(42L);

        CreditWorkbenchResponse.Exposure exposure = workbench.exposure();
        assertThat(exposure.loans()).extracting(CreditWorkbenchResponse.OtherLoan::id).containsExactly(8L, 12L, 20L, 30L);
        assertThat(exposure.loans()).extracting(CreditWorkbenchResponse.OtherLoan::open)
                .containsExactly(false, true, false, true);
        assertThat(exposure.loans()).extracting(CreditWorkbenchResponse.OtherLoan::stage)
                .containsExactly(LoanStage.PAID, LoanStage.PAID, LoanStage.DECLINED, LoanStage.WITH_SSB);
        assertThat(exposure.loans().get(1).reference()).isEqualTo("000000012");
        assertThat(exposure.loans().get(1).repaymentEndDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(exposure.openLoans()).isEqualTo(2);
        assertThat(exposure.openPrincipal()).isEqualByComparingTo("419.15");
        assertThat(exposure.openMonthlyDeduction()).isEqualByComparingTo("164.67");
        assertThat(workbench.flags()).containsExactly(new CreditWorkbenchResponse.Flag("OTHER_OPEN_LOANS",
                "2 other open loan(s) with InnBucks, deducting 164.67 a month"));
    }

    @Test
    @DisplayName("a paid loan is open until its last deduction has passed, by SSB's end date before the loan's own")
    void paidLoanOpenUntilItsLastDeduction() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        Loan paid = other(12L, LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED, "319.15", "125.38");
        paid.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);

        assertThat(CreditWorkbenchService.isOpen(paid, LoanStage.PAID, today)).isTrue();
        paid.setLoanEndDate(today.minusDays(1));
        assertThat(CreditWorkbenchService.isOpen(paid, LoanStage.PAID, today)).isFalse();
        paid.setRepaymentEndDate(today);
        assertThat(CreditWorkbenchService.isOpen(paid, LoanStage.PAID, today)).isTrue();
        assertThat(CreditWorkbenchService.isOpen(paid, LoanStage.NOT_COMPLETED, today)).isFalse();
        assertThat(CreditWorkbenchService.isOpen(paid, LoanStage.PAYOUT_DELAYED, today)).isTrue();
    }

    @Test
    @DisplayName("every flag the system holds is raised, for the officer to weigh")
    void flags() {
        Loan loan = loan42();
        loan.setPayslipReviewStatus(PayslipReviewStatus.PENDING);
        loan.setDeductionCancellationStatus(DeductionCancellationStatus.REQUIRED);
        loan.setDeductionCancellationReason(DeductionCancellationService.REASON_CREDIT_REJECTED);
        LoanResponse view = viewed(loan);
        view.setDocuments(List.of(
                new LoanDocumentSummary(DocumentType.PAYSLIP, 2, DocumentOrigin.AMENDMENT, "application/pdf", 231402,
                        "cd43", "August payslip", "tmoyo", LocalDateTime.of(2026, 9, 30, 7, 58, 20)),
                new LoanDocumentSummary(DocumentType.NATIONAL_ID, 1, DocumentOrigin.APPLICATION, "image/jpeg", 412903,
                        "0321", null, "tmoyo", LocalDateTime.of(2026, 9, 29, 8, 15, 30))));
        LocalDateTime reached = LocalDateTime.now(ZoneOffset.UTC).minusHours(50);
        view.setCreditTurnaround(new CreditTurnaround(reached, reached.plusHours(24), reached.plusHours(48),
                new BigDecimal("50.0"), true, reached.plusHours(48), null));
        when(payslipFraudFlagRepository.findByLoanIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                PayslipFraudFlag.builder().loanId(42L).reason(PayslipFraudReason.PAYSLIP_REUSED_BY_ANOTHER_APPLICANT)
                        .matchedLoanId(17L).detail("Same payslip file as loan 000000017").build()));
        when(loanRepository.isHeldForEmploymentEvent(42L)).thenReturn(true);
        when(checkpointGate.pending(any())).thenReturn(List.of(WorkflowStage.builder().code("SECOND_LOOK")
                .name("Second look").holdPoint(HoldPoint.BEFORE_CREDIT_APPROVAL).build()));

        CreditWorkbenchResponse workbench = service.workbench(42L);

        assertThat(codes(workbench)).containsExactly("PAYSLIP_REVIEW_PENDING", "PAYSLIP_REUSED_BY_ANOTHER_APPLICANT",
                "DOCUMENTS_AMENDED", "EMPLOYMENT_EVENT_HOLD", "CHECKPOINT_PENDING", "DEDUCTION_CANCELLATION_REQUIRED",
                "CREDIT_DECISION_OVERDUE", "CREDIT_DECISION_ESCALATED");
        assertThat(workbench.flags().get(1).detail()).isEqualTo("Same payslip file as loan 000000017");
        assertThat(workbench.flags().get(2).detail())
                .isEqualTo("PAYSLIP replaced after the application (version 2) by tmoyo");
        assertThat(workbench.flags().get(4).detail())
                .isEqualTo("Held at Second look; it cannot be approved until it is cleared there");
        assertThat(workbench.flags().get(5).detail()).contains("CREDIT_REJECTED");
        assertThat(workbench.flags().get(6).detail()).isEqualTo("Waiting 50.0 hours for a decision, past its target");
    }

    @Test
    @DisplayName("a clean application raises nothing, and brings its employment events and decisions with it")
    void cleanApplication() {
        Loan loan = loan42();
        LoanResponse view = viewed(loan);
        CreditDecisionResponse returned = new CreditDecisionResponse(17L, CreditAction.RETURNED, "RETURN_PAYSLIP",
                "Payslip missing, unclear or out of date", "Payslip is for June", "cmanager",
                LocalDateTime.of(2026, 9, 30, 7, 12, 45), "{}", "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a");
        when(creditDecisionService.history(42L)).thenReturn(List.of(returned));
        when(employmentEventService.forLoan(42L, LoanReadScope.platform())).thenReturn(List.of());

        CreditWorkbenchResponse workbench = service.workbench(42L);

        assertThat(workbench.loan()).isSameAs(view);
        assertThat(workbench.flags()).isEmpty();
        assertThat(workbench.exposure().openLoans()).isZero();
        assertThat(workbench.exposure().loans()).isEmpty();
        assertThat(workbench.decisions()).containsExactly(returned);
        assertThat(workbench.employmentEvents()).isEmpty();
    }

    @Test
    @DisplayName("an unknown loan is a NotFoundException, and nothing else is read")
    void unknownLoan() {
        when(loanRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.workbench(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 99 not found");
        verifyNoInteractions(loanService, creditDecisionService, employmentEventService, payslipFraudFlagRepository);
    }
}
