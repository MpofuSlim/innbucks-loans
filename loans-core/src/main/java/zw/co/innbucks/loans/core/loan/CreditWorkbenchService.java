package zw.co.innbucks.loans.core.loan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.document.DocumentOrigin;
import zw.co.innbucks.loans.core.document.LoanDocumentSummary;
import zw.co.innbucks.loans.core.employment.EmploymentEventService;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.turnaround.CreditTurnaround;
import zw.co.innbucks.loans.core.workflow.CheckpointGate;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Assembles the credit workbench for one application (FR-SSB-015 / FR-PBL-026). It reads what other services already
 * hold and decides nothing: a flag is something for the officer to look at, never a refusal.
 */
@Service
@RequiredArgsConstructor
public class CreditWorkbenchService {

    static final String PAYSLIP_REVIEW_PENDING = "PAYSLIP_REVIEW_PENDING";
    static final String DOCUMENTS_AMENDED = "DOCUMENTS_AMENDED";
    static final String EMPLOYMENT_EVENT_HOLD = "EMPLOYMENT_EVENT_HOLD";
    static final String DEDUCTION_CANCELLATION_REQUIRED = "DEDUCTION_CANCELLATION_REQUIRED";
    static final String OTHER_OPEN_LOANS = "OTHER_OPEN_LOANS";
    static final String CREDIT_DECISION_OVERDUE = "CREDIT_DECISION_OVERDUE";
    static final String CREDIT_DECISION_ESCALATED = "CREDIT_DECISION_ESCALATED";
    static final String CHECKPOINT_PENDING = "CHECKPOINT_PENDING";

    public static final String AFFORDABILITY_NOT_ASSESSED = "The SSB deduction cap and minimum take-home pay are not"
            + " configured yet, so affordability is not passed or failed; the figures are for the officer to judge.";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final LoanRepository loanRepository;
    private final LoanService loanService;
    private final CreditDecisionService creditDecisionService;
    private final EmploymentEventService employmentEventService;
    private final PayslipFraudFlagRepository payslipFraudFlagRepository;
    private final MarketTimeZone marketTimeZone;
    private final CheckpointGate checkpointGate;

    /**
     * The workbench for a loan, as it stands now.
     *
     * @throws NotFoundException no such loan
     */
    @Transactional(readOnly = true)
    public CreditWorkbenchResponse workbench(Long loanId) {
        Loan loan = loanRepository.findById(loanId)
                .orElseThrow(() -> new NotFoundException("Loan " + loanId + " not found"));
        LoanResponse view = loanService.getLoan(loanId, LoanReadScope.platform());
        CreditWorkbenchResponse.Exposure exposure = exposureOf(loan);
        return new CreditWorkbenchResponse(
                view,
                affordabilityOf(loan),
                exposure,
                flagsOf(loan, view, exposure),
                employmentEventService.forLoan(loanId, LoanReadScope.platform()),
                creditDecisionService.history(loanId));
    }

    /** The payslip's figures against the deduction SSB is instructed to take for this loan. */
    static CreditWorkbenchResponse.Affordability affordabilityOf(Loan loan) {
        EmploymentDetail employment = loan.getEmploymentDetail();
        BigDecimal gross = employment == null ? null : employment.getGrossSalary();
        BigDecimal net = employment == null ? null : employment.getNetSalary();
        BigDecimal payslipDeductions = loan.getPayslipDeductions().stream()
                .map(PayslipDeduction::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deduction = monthlyDeductionOf(loan);
        BigDecimal netAfter = net == null || deduction == null ? null : net.subtract(deduction);
        BigDecimal percent = net == null || deduction == null || net.signum() <= 0
                ? null
                : deduction.multiply(HUNDRED).divide(net, 1, RoundingMode.HALF_UP);
        return new CreditWorkbenchResponse.Affordability(gross, net, payslipDeductions, deduction, netAfter, percent,
                CreditWorkbenchResponse.AffordabilityOutcome.NOT_ASSESSED, AFFORDABILITY_NOT_ASSESSED);
    }

    /** What SSB deducts each month: the grossed-up deduction lodged with it, else the instalment. */
    private static BigDecimal monthlyDeductionOf(Loan loan) {
        return loan.getGrossedMonthlyDeduction() != null ? loan.getGrossedMonthlyDeduction() : loan.getMonthlyInstallment();
    }

    /** The applicant's other loans, found by EC number and by national ID, oldest first. */
    CreditWorkbenchResponse.Exposure exposureOf(Loan loan) {
        Map<Long, Loan> others = new TreeMap<>();
        if (loan.getEcNumber() != null && !loan.getEcNumber().isBlank()) {
            loanRepository.findByEcNumberOrderByIdAsc(loan.getEcNumber()).forEach(other -> others.put(other.getId(), other));
        }
        if (loan.getNationalIdNumber() != null && !loan.getNationalIdNumber().isBlank()) {
            loanRepository.findByNationalIdNumberOrderByIdAsc(loan.getNationalIdNumber())
                    .forEach(other -> others.put(other.getId(), other));
        }
        others.remove(loan.getId());

        LocalDate today = marketTimeZone.today();
        List<CreditWorkbenchResponse.OtherLoan> loans = new ArrayList<>();
        int open = 0;
        BigDecimal openPrincipal = BigDecimal.ZERO;
        BigDecimal openDeduction = BigDecimal.ZERO;
        for (Loan other : others.values()) {
            LoanStage stage = LoanStage.of(other);
            boolean isOpen = isOpen(other, stage, today);
            BigDecimal deduction = monthlyDeductionOf(other);
            if (isOpen) {
                open++;
                openPrincipal = openPrincipal.add(zeroIfNull(other.getPrincipal()));
                openDeduction = openDeduction.add(zeroIfNull(deduction));
            }
            loans.add(new CreditWorkbenchResponse.OtherLoan(other.getId(), other.getReference(), stage, isOpen,
                    other.getPrincipal(), deduction, other.getTenor(), other.getCreatedDate(), other.getDateDisbursed(),
                    other.finalDeductionDate()));
        }
        loans.sort(Comparator.comparing(CreditWorkbenchResponse.OtherLoan::id));
        return new CreditWorkbenchResponse.Exposure(open, openPrincipal, openDeduction, loans);
    }

    /** Neither declined nor abandoned, and not paid out with its last deduction already past. */
    static boolean isOpen(Loan loan, LoanStage stage, LocalDate today) {
        if (stage == LoanStage.DECLINED || stage == LoanStage.NOT_COMPLETED) {
            return false;
        }
        LocalDate lastDeduction = loan.finalDeductionDate();
        return stage != LoanStage.PAID || lastDeduction == null || !lastDeduction.isBefore(today);
    }

    private List<CreditWorkbenchResponse.Flag> flagsOf(Loan loan, LoanResponse view,
                                                       CreditWorkbenchResponse.Exposure exposure) {
        List<CreditWorkbenchResponse.Flag> flags = new ArrayList<>();
        if (loan.getPayslipReviewStatus() == PayslipReviewStatus.PENDING) {
            flags.add(new CreditWorkbenchResponse.Flag(PAYSLIP_REVIEW_PENDING,
                    "Held for payslip review; it is not lodged with SSB until the review clears it"));
        }
        for (PayslipFraudFlag finding : payslipFraudFlagRepository.findByLoanIdInOrderByIdAsc(List.of(loan.getId()))) {
            flags.add(new CreditWorkbenchResponse.Flag(finding.getReason().name(), finding.getDetail()));
        }
        if (view.getDocuments() != null) {
            view.getDocuments().stream()
                    .filter(document -> document.origin() == DocumentOrigin.AMENDMENT)
                    .sorted(Comparator.comparing(LoanDocumentSummary::documentType))
                    .forEach(document -> flags.add(new CreditWorkbenchResponse.Flag(DOCUMENTS_AMENDED,
                            document.documentType() + " replaced after the application (version " + document.version()
                                    + ") by " + document.uploadedBy())));
        }
        if (loanRepository.isHeldForEmploymentEvent(loan.getId())) {
            flags.add(new CreditWorkbenchResponse.Flag(EMPLOYMENT_EVENT_HOLD,
                    "Held for an employment event; it cannot be approved until the event is resolved"));
        }
        for (WorkflowStage checkpoint : checkpointGate.pending(loan)) {
            flags.add(new CreditWorkbenchResponse.Flag(CHECKPOINT_PENDING, "Held at " + checkpoint.getName() + "; "
                    + switch (checkpoint.getHoldPoint()) {
                        case BEFORE_LODGEMENT -> "it is not lodged with SSB";
                        case BEFORE_CREDIT_APPROVAL -> "it cannot be approved";
                        case BEFORE_BOOKING -> "it is not booked or paid";
                    } + " until it is cleared there"));
        }
        if (loan.getDeductionCancellationStatus() == DeductionCancellationStatus.REQUIRED) {
            flags.add(new CreditWorkbenchResponse.Flag(DEDUCTION_CANCELLATION_REQUIRED,
                    "The deduction lodged with SSB must be cancelled (" + loan.getDeductionCancellationReason() + ")"));
        }
        if (exposure.openLoans() > 0) {
            flags.add(new CreditWorkbenchResponse.Flag(OTHER_OPEN_LOANS, exposure.openLoans()
                    + " other open loan(s) with InnBucks, deducting " + exposure.openMonthlyDeduction() + " a month"));
        }
        CreditTurnaround turnaround = view.getCreditTurnaround();
        if (turnaround != null && turnaround.overdue()) {
            flags.add(new CreditWorkbenchResponse.Flag(CREDIT_DECISION_OVERDUE,
                    "Waiting " + turnaround.waitingHours() + " hours for a decision, past its target"));
        }
        if (turnaround != null && turnaround.escalatedAt() != null) {
            flags.add(new CreditWorkbenchResponse.Flag(CREDIT_DECISION_ESCALATED,
                    "Escalated for waiting past the escalation point"));
        }
        return flags;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
