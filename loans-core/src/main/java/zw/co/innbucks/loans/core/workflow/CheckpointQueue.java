package zw.co.innbucks.loans.core.workflow;

import zw.co.innbucks.loans.core.disbursements.BookingFailureKind;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The loans a checkpoint holds (FR-SSB-014): those at its point that it applies to and has not decided. A loan's wait
 * begins when it reached the point, or when the checkpoint became active if that was later. Built per checkpoint by
 * {@link StageQueues}, since checkpoints are added at runtime.
 */
class CheckpointQueue implements StageQueue {

    private final WorkflowStage stage;
    private final LoanRepository loanRepository;
    private final CheckpointDecisionRepository checkpointDecisionRepository;

    CheckpointQueue(WorkflowStage stage, LoanRepository loanRepository,
                    CheckpointDecisionRepository checkpointDecisionRepository) {
        this.stage = stage;
        this.loanRepository = loanRepository;
        this.checkpointDecisionRepository = checkpointDecisionRepository;
    }

    @Override
    public List<Waiting> waiting() {
        if (!stage.isActive()) {
            return List.of();
        }
        List<Loan> candidates = loansAt(stage.getHoldPoint()).stream().filter(stage::appliesTo).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<Long> decided = checkpointDecisionRepository
                .findByStageCodeInAndLoanIdIn(List.of(stage.getCode()), candidates.stream().map(Loan::getId).toList())
                .stream().map(CheckpointDecision::getLoanId).collect(Collectors.toSet());
        return candidates.stream()
                .filter(loan -> !decided.contains(loan.getId()))
                .map(loan -> new Waiting(loan, enteredAt(stage, loan)))
                .sorted(Comparator.comparing(Waiting::enteredAt).thenComparing(wait -> wait.loan().getId()))
                .toList();
    }

    @Override
    public Optional<LocalDateTime> enteredAt(Loan loan) {
        if (!stage.isActive() || !atHoldPoint(stage.getHoldPoint(), loan) || !stage.appliesTo(loan)
                || checkpointDecisionRepository.existsByStageCodeAndLoanId(stage.getCode(), loan.getId())) {
            return Optional.empty();
        }
        return Optional.of(enteredAt(stage, loan));
    }

    @Override
    public List<Visit> endedBetween(LocalDateTime from, LocalDateTime to) {
        return checkpointDecisionRepository.findByStageCodeAndDecidedAtBetweenOrderByIdAsc(stage.getCode(), from, to)
                .stream()
                .map(decision -> new Visit(decision.getLoanId(), decision.getEnteredAt(), decision.getDecidedAt(),
                        decision.getOutcome().name()))
                .toList();
    }

    private List<Loan> loansAt(HoldPoint point) {
        return switch (point) {
            case BEFORE_LODGEMENT -> loanRepository.findBeforeLodgement();
            case BEFORE_CREDIT_APPROVAL -> loanRepository.findBeforeCreditApproval();
            case BEFORE_BOOKING -> loanRepository.findBeforeBooking();
        };
    }

    /**
     * Whether the loan is at the point, not yet past it. A loan being lodged or booked at this moment is past it: the
     * request may already have left, so holding it now would hold nothing. So is a loan whose booking had an unknown
     * outcome, which may already have paid it.
     */
    static boolean atHoldPoint(HoldPoint point, Loan loan) {
        return switch (point) {
            case BEFORE_LODGEMENT -> loan.getLoanApprovalStatus() == LoanApprovalStatus.NEW
                    && loan.getInternalApprovalStatus() != InternalApprovalStatus.REJECTED
                    && loan.getLodgementClaimedAt() == null;
            case BEFORE_CREDIT_APPROVAL -> loan.getLoanApprovalStatus() == LoanApprovalStatus.APPROVED
                    && (loan.getInternalApprovalStatus() == InternalApprovalStatus.PENDING
                    || loan.getInternalApprovalStatus() == InternalApprovalStatus.RETURNED);
            case BEFORE_BOOKING -> loan.getLoanApprovalStatus() == LoanApprovalStatus.APPROVED
                    && loan.getInternalApprovalStatus() == InternalApprovalStatus.APPROVED
                    && loan.getLoanAccountStatus() == LoanAccountStatus.PENDING
                    && loan.getBookingClaimedAt() == null
                    && loan.getDisbursementStatus() != LoanDisbursementStatus.SUCCESS
                    && loan.getBookingFailureKind() != BookingFailureKind.AMBIGUOUS;
        };
    }

    /** When the loan reached the point: its capture, SSB's acceptance, or Credit's approval. */
    static LocalDateTime reachedAt(HoldPoint point, Loan loan) {
        return switch (point) {
            case BEFORE_LODGEMENT -> loan.getCreatedDate();
            case BEFORE_CREDIT_APPROVAL -> loan.getDateApproved();
            case BEFORE_BOOKING -> loan.getInternalApprovalDate();
        };
    }

    /** From when the loan reached the point, or from when the checkpoint became active if that was later. */
    static LocalDateTime enteredAt(WorkflowStage stage, Loan loan) {
        LocalDateTime reached = reachedAt(stage.getHoldPoint(), loan);
        LocalDateTime activeSince = stage.getActiveSince();
        return reached == null || reached.isBefore(activeSince) ? activeSince : reached;
    }
}
