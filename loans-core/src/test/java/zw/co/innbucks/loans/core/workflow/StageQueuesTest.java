package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEvent;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventAction;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventOutcome;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventRepository;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventStatus;
import zw.co.innbucks.loans.core.loan.CreditAction;
import zw.co.innbucks.loans.core.loan.CreditDecision;
import zw.co.innbucks.loans.core.loan.CreditDecisionRepository;
import zw.co.innbucks.loans.core.loan.DeductionCancellationStatus;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.PayslipFraudFlag;
import zw.co.innbucks.loans.core.loan.PayslipFraudFlagRepository;
import zw.co.innbucks.loans.core.loan.PayslipFraudReason;
import zw.co.innbucks.loans.core.loan.PayslipReviewStatus;
import zw.co.innbucks.loans.core.workflow.StageQueue.Visit;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Each stage's queue is read off the loans' own state, and each wait is named by when it began (FR-SSB-014). */
class StageQueuesTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 30, 6, 0);
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 9, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 9, 30, 23, 59);

    private final LoanRepository loanRepository = mock(LoanRepository.class);

    private static Loan loan(long id) {
        return WorkflowFixtures.loan(id, "tmoyo");
    }

    @Nested
    @DisplayName("payslip review")
    class PayslipReview {

        private final PayslipFraudFlagRepository flags = mock(PayslipFraudFlagRepository.class);
        private final PayslipReviewQueue queue = new PayslipReviewQueue(loanRepository, flags);

        private PayslipFraudFlag flag(long loanId, LocalDateTime at) {
            return PayslipFraudFlag.builder().loanId(loanId).reason(PayslipFraudReason.PAYSLIP_REUSED_BY_SAME_APPLICANT)
                    .createdAt(at).build();
        }

        @Test
        @DisplayName("a hold waits from its latest findings: an amended payslip that raised new ones starts a new wait")
        void waitsFromLatestFindings() {
            Loan amended = loan(41L);
            amended.setPayslipReviewStatus(PayslipReviewStatus.PENDING);
            Loan held = loan(42L);
            held.setPayslipReviewStatus(PayslipReviewStatus.PENDING);
            when(loanRepository.findByPayslipReviewStatusOrderByIdAsc(PayslipReviewStatus.PENDING))
                    .thenReturn(List.of(amended, held));
            when(flags.findByLoanIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                    flag(41L, T0), flag(41L, T0.plusDays(2)), flag(42L, T0.plusHours(1))));

            assertThat(queue.waiting()).extracting(wait -> wait.loan().getId(), StageQueue.Waiting::enteredAt)
                    .containsExactly(tuple(42L, T0.plusHours(1)), tuple(41L, T0.plusDays(2)));
        }

        @Test
        @DisplayName("a review ended when it was decided, timed from the findings it decided; none pending, no wait")
        void endedWaits() {
            Loan reviewed = loan(42L);
            reviewed.setPayslipReviewStatus(PayslipReviewStatus.CLEARED);
            reviewed.setPayslipReviewedAt(T0.plusHours(5));
            when(loanRepository.findByPayslipReviewedAtBetween(FROM, TO)).thenReturn(List.of(reviewed));
            when(flags.findByLoanIdInOrderByIdAsc(anyCollection()))
                    .thenReturn(List.of(flag(42L, T0), flag(42L, T0.plusDays(3))));

            assertThat(queue.endedBetween(FROM, TO))
                    .containsExactly(new Visit(42L, T0, T0.plusHours(5), "CLEARED"));
            assertThat(queue.enteredAt(reviewed)).isEmpty();
        }
    }

    @Nested
    @DisplayName("credit decision")
    class CreditDecisionStage {

        private final CreditDecisionRepository decisions = mock(CreditDecisionRepository.class);
        private final CreditDecisionQueue queue = new CreditDecisionQueue(loanRepository, decisions);

        private Loan awaiting(long id, LocalDateTime approvedAt) {
            Loan loan = loan(id);
            loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
            loan.setInternalApprovalStatus(InternalApprovalStatus.PENDING);
            loan.setDateApproved(approvedAt);
            return loan;
        }

        @Test
        @DisplayName("a loan waits from SSB's approval, or its last resubmission; oldest wait first")
        void waiting() {
            Loan resubmitted = awaiting(41L, T0.minusDays(3));
            resubmitted.setCreditResubmittedAt(T0.plusHours(2));
            Loan fresh = awaiting(42L, T0);
            Loan unknown = awaiting(43L, null);
            when(loanRepository.findAwaitingCreditDecision()).thenReturn(List.of(resubmitted, fresh, unknown));

            assertThat(queue.waiting()).extracting(wait -> wait.loan().getId(), StageQueue.Waiting::enteredAt)
                    .containsExactly(tuple(42L, T0), tuple(41L, T0.plusHours(2)));
            assertThat(queue.enteredAt(resubmitted)).contains(T0.plusHours(2));
            fresh.setInternalApprovalStatus(InternalApprovalStatus.RETURNED);
            assertThat(queue.enteredAt(fresh)).isEmpty();
        }

        @Test
        @DisplayName("each decision is timed from the wait it ended; one before SSB answered cannot be timed")
        void endedWaits() {
            when(decisions.findByActionInAndPerformedAtBetweenOrderByIdAsc(anyCollection(), eq(FROM), eq(TO)))
                    .thenReturn(List.of(
                            decision(17, 42, CreditAction.RETURNED, T0.plusHours(1)),
                            decision(19, 42, CreditAction.APPROVED, T0.plusHours(5)),
                            decision(20, 45, CreditAction.REJECTED, T0)));
            List<Object[]> approved = new ArrayList<>();
            approved.add(new Object[]{42L, T0});
            approved.add(new Object[]{45L, null});
            when(loanRepository.findDateApprovedByIdIn(anyCollection())).thenReturn(approved);
            when(decisions.findByLoanIdInAndActionOrderByPerformedAtAsc(anyCollection(), eq(CreditAction.RESUBMITTED)))
                    .thenReturn(List.of(decision(18, 42, CreditAction.RESUBMITTED, T0.plusHours(3))));

            assertThat(queue.endedBetween(FROM, TO)).containsExactly(
                    new Visit(42L, T0, T0.plusHours(1), "RETURNED"),
                    new Visit(42L, T0.plusHours(3), T0.plusHours(5), "APPROVED"),
                    new Visit(45L, null, T0, "REJECTED"));
        }

        @Test
        @DisplayName("a decision's wait began at the last resubmission before it, else at SSB's approval if earlier")
        void enteredBefore() {
            CreditDecision decided = decision(1, 42, CreditAction.APPROVED, T0.plusHours(10));
            List<LocalDateTime> resubmissions = List.of(T0.plusHours(2), T0.plusHours(5), T0.plusHours(12));

            assertThat(CreditDecisionQueue.enteredBefore(decided, T0, resubmissions)).isEqualTo(T0.plusHours(5));
            assertThat(CreditDecisionQueue.enteredBefore(decided, T0, List.of())).isEqualTo(T0);
            assertThat(CreditDecisionQueue.enteredBefore(decided, T0.plusHours(11), List.of())).isNull();
            assertThat(CreditDecisionQueue.enteredBefore(decided, null, List.of())).isNull();
        }
    }

    @Nested
    @DisplayName("more information")
    class MoreInformation {

        private final CreditDecisionRepository decisions = mock(CreditDecisionRepository.class);
        private final MoreInformationQueue queue = new MoreInformationQueue(loanRepository, decisions);

        @Test
        @DisplayName("a returned loan waits on its originator from the return")
        void waiting() {
            Loan returned = loan(42L);
            returned.setInternalApprovalStatus(InternalApprovalStatus.RETURNED);
            returned.setInternalApprovalDate(T0);
            when(loanRepository.findByInternalApprovalStatusOrderByIdAsc(InternalApprovalStatus.RETURNED))
                    .thenReturn(List.of(returned));

            assertThat(queue.waiting()).extracting(StageQueue.Waiting::enteredAt).containsExactly(T0);
            assertThat(queue.enteredAt(returned)).contains(T0);
        }

        @Test
        @DisplayName("a wait ends at the answer, or at a rejection of the returned loan; not at any other rejection")
        void endedWaits() {
            when(decisions.findByActionInAndPerformedAtBetweenOrderByIdAsc(anyCollection(), eq(FROM), eq(TO)))
                    .thenReturn(List.of(decision(18, 42, CreditAction.RESUBMITTED, T0.plusHours(2)),
                            decision(31, 43, CreditAction.REJECTED, T0.plusDays(1)),
                            decision(41, 44, CreditAction.REJECTED, T0.plusDays(2))));
            when(decisions.findByLoanIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                    decision(17, 42, CreditAction.RETURNED, T0), decision(18, 42, CreditAction.RESUBMITTED, T0.plusHours(2)),
                    decision(30, 43, CreditAction.RETURNED, T0.plusHours(3)),
                    decision(31, 43, CreditAction.REJECTED, T0.plusDays(1)),
                    decision(41, 44, CreditAction.REJECTED, T0.plusDays(2))));

            assertThat(queue.endedBetween(FROM, TO)).containsExactly(
                    new Visit(42L, T0, T0.plusHours(2), "RESUBMITTED"),
                    new Visit(43L, T0.plusHours(3), T0.plusDays(1), "REJECTED"));
        }
    }

    @Nested
    @DisplayName("employment event review")
    class EmploymentEventReview {

        private final LoanEmploymentEventRepository rows = mock(LoanEmploymentEventRepository.class);
        private final EmploymentEventReviewQueue queue = new EmploymentEventReviewQueue(loanRepository, rows);

        private LoanEmploymentEvent row(long loanId, LocalDateTime createdAt, LoanEmploymentEventStatus status) {
            return LoanEmploymentEvent.builder().eventId(5L).loanId(loanId).action(LoanEmploymentEventAction.HOLD)
                    .status(status).createdAt(createdAt).build();
        }

        @Test
        @DisplayName("a loan waits from its oldest open hold or review")
        void waiting() {
            Loan held = loan(42L);
            when(rows.findByStatusOrderByIdAsc(LoanEmploymentEventStatus.OPEN)).thenReturn(List.of(
                    row(42L, T0.plusHours(2), LoanEmploymentEventStatus.OPEN),
                    row(42L, T0, LoanEmploymentEventStatus.OPEN)));
            when(loanRepository.findAllById(any())).thenReturn(List.of(held));
            when(rows.findByLoanIdOrderByIdAsc(42L)).thenReturn(List.of(
                    row(42L, T0.minusDays(1), LoanEmploymentEventStatus.CLOSED),
                    row(42L, T0, LoanEmploymentEventStatus.OPEN)));

            assertThat(queue.waiting()).extracting(StageQueue.Waiting::enteredAt).containsExactly(T0);
            assertThat(queue.enteredAt(held)).contains(T0);
        }

        @Test
        @DisplayName("each resolved hold or review is a wait, ending in its outcome")
        void endedWaits() {
            LoanEmploymentEvent resolved = LoanEmploymentEvent.builder().eventId(5L).loanId(42L)
                    .action(LoanEmploymentEventAction.HOLD).status(LoanEmploymentEventStatus.CLOSED)
                    .outcome(LoanEmploymentEventOutcome.RELEASED).resolvedAt(T0.plusHours(20)).createdAt(T0).build();
            when(rows.findByResolvedAtBetweenOrderByIdAsc(FROM, TO)).thenReturn(List.of(resolved));

            assertThat(queue.endedBetween(FROM, TO)).containsExactly(new Visit(42L, T0, T0.plusHours(20), "RELEASED"));
        }
    }

    @Nested
    @DisplayName("deduction cancellation")
    class DeductionCancellation {

        private final DeductionCancellationQueue queue = new DeductionCancellationQueue(loanRepository);

        @Test
        @DisplayName("a cancellation waits from when it was flagged, and ends when it is recorded done")
        void waitingAndEnded() {
            Loan required = loan(42L);
            required.setDeductionCancellationStatus(DeductionCancellationStatus.REQUIRED);
            required.setDeductionCancellationRequestedAt(T0);
            when(loanRepository.findByDeductionCancellationStatusOrderByDeductionCancellationRequestedAtAscIdAsc(
                    DeductionCancellationStatus.REQUIRED)).thenReturn(List.of(required));
            Loan cancelled = loan(43L);
            cancelled.setDeductionCancellationStatus(DeductionCancellationStatus.CANCELLED_EXTERNALLY);
            cancelled.setDeductionCancellationRequestedAt(T0);
            cancelled.setDeductionCancelledAt(T0.plusHours(6));
            when(loanRepository.findByDeductionCancelledAtBetween(FROM, TO)).thenReturn(List.of(cancelled));

            assertThat(queue.waiting()).extracting(StageQueue.Waiting::enteredAt).containsExactly(T0);
            assertThat(queue.enteredAt(required)).contains(T0);
            assertThat(queue.enteredAt(cancelled)).isEmpty();
            assertThat(queue.endedBetween(FROM, TO))
                    .containsExactly(new Visit(43L, T0, T0.plusHours(6), "CANCELLED_EXTERNALLY"));
        }
    }

    @Test
    @DisplayName("every stage has exactly one queue, or the application does not start")
    void everyStageHasOneQueue() {
        CreditDecisionRepository decisions = mock(CreditDecisionRepository.class);
        List<SystemStageQueue> all = List.of(
                new PayslipReviewQueue(loanRepository, mock(PayslipFraudFlagRepository.class)),
                new CreditDecisionQueue(loanRepository, decisions), new MoreInformationQueue(loanRepository, decisions),
                new EmploymentEventReviewQueue(loanRepository, mock(LoanEmploymentEventRepository.class)),
                new DeductionCancellationQueue(loanRepository));

        CheckpointDecisionRepository checkpointDecisions = mock(CheckpointDecisionRepository.class);
        assertThat(new StageQueues(all, loanRepository, checkpointDecisions).of(SystemStage.MORE_INFORMATION))
                .isInstanceOf(MoreInformationQueue.class);
        assertThatThrownBy(() -> new StageQueues(all.subList(0, 4), loanRepository, checkpointDecisions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No queue for workflow stage DEDUCTION_CANCELLATION");
        List<SystemStageQueue> twice = new ArrayList<>(all);
        twice.add(new DeductionCancellationQueue(loanRepository));
        assertThatThrownBy(() -> new StageQueues(twice, loanRepository, checkpointDecisions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Two queues for workflow stage DEDUCTION_CANCELLATION");
    }

    @Test
    @DisplayName("a checkpoint's queue is built for it as it is asked for")
    void checkpointQueue() {
        CreditDecisionRepository decisions = mock(CreditDecisionRepository.class);
        List<SystemStageQueue> all = List.of(
                new PayslipReviewQueue(loanRepository, mock(PayslipFraudFlagRepository.class)),
                new CreditDecisionQueue(loanRepository, decisions), new MoreInformationQueue(loanRepository, decisions),
                new EmploymentEventReviewQueue(loanRepository, mock(LoanEmploymentEventRepository.class)),
                new DeductionCancellationQueue(loanRepository));
        StageQueues queues = new StageQueues(all, loanRepository, mock(CheckpointDecisionRepository.class));

        assertThat(queues.of(WorkflowFixtures.checkpoint("PAYOUT_CHECK", HoldPoint.BEFORE_BOOKING)))
                .isInstanceOf(CheckpointQueue.class);
        assertThat(queues.of(WorkflowFixtures.creditDecision(AssignmentMode.OPTIONAL)))
                .isInstanceOf(CreditDecisionQueue.class);
    }

    private static CreditDecision decision(long id, long loanId, CreditAction action, LocalDateTime at) {
        return CreditDecision.builder().id(id).loanId(loanId).action(action).performedAt(at).build();
    }
}
