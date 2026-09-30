package zw.co.innbucks.loans.core.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.disbursements.BookingFailureKind;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which loans a checkpoint holds, and since when (FR-SSB-014). */
class CheckpointQueueTest {

    private static final LocalDateTime ACTIVE_SINCE = WorkflowFixtures.CHECKPOINT_ACTIVE_SINCE;

    private LoanRepository loanRepository;
    private CheckpointDecisionRepository decisions;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        decisions = mock(CheckpointDecisionRepository.class);
    }

    private CheckpointQueue queue(WorkflowStage stage) {
        return new CheckpointQueue(stage, loanRepository, decisions);
    }

    private static Loan approvedForBooking(long id, LocalDateTime approvedAt) {
        Loan loan = WorkflowFixtures.loan(id, "tmoyo");
        loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        loan.setInternalApprovalStatus(InternalApprovalStatus.APPROVED);
        loan.setInternalApprovalDate(approvedAt);
        loan.setLoanAccountStatus(LoanAccountStatus.PENDING);
        loan.setPrincipal(new BigDecimal("2500.00"));
        return loan;
    }

    @Test
    @DisplayName("before lodgement: a NEW loan that is neither declined nor being lodged right now")
    void beforeLodgement() {
        Loan loan = WorkflowFixtures.loan(1, "tmoyo");
        loan.setLoanApprovalStatus(LoanApprovalStatus.NEW);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_LODGEMENT, loan)).isTrue();

        loan.setLodgementClaimedAt(ACTIVE_SINCE);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_LODGEMENT, loan)).as("being lodged").isFalse();
        loan.setLodgementClaimedAt(null);
        loan.setInternalApprovalStatus(InternalApprovalStatus.REJECTED);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_LODGEMENT, loan)).as("declined").isFalse();
        loan.setInternalApprovalStatus(null);
        loan.setLoanApprovalStatus(LoanApprovalStatus.PROCESSING);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_LODGEMENT, loan)).as("lodged").isFalse();
    }

    @Test
    @DisplayName("before credit approval: accepted by SSB and undecided, including while returned for information")
    void beforeCreditApproval() {
        Loan loan = WorkflowFixtures.loan(1, "tmoyo");
        loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        for (InternalApprovalStatus status : InternalApprovalStatus.values()) {
            loan.setInternalApprovalStatus(status);
            assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_CREDIT_APPROVAL, loan)).as(status.name())
                    .isEqualTo(status == InternalApprovalStatus.PENDING || status == InternalApprovalStatus.RETURNED);
        }
        loan.setInternalApprovalStatus(InternalApprovalStatus.PENDING);
        loan.setLoanApprovalStatus(LoanApprovalStatus.PROCESSING);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_CREDIT_APPROVAL, loan)).as("SSB undecided").isFalse();
    }

    @Test
    @DisplayName("before booking: approved and unbooked; being booked, paid, or booked with an unknown outcome is"
            + " past it")
    void beforeBooking() {
        Loan loan = approvedForBooking(1, ACTIVE_SINCE);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_BOOKING, loan)).isTrue();

        loan.setBookingClaimedAt(ACTIVE_SINCE);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_BOOKING, loan)).as("being booked").isFalse();
        loan.setBookingClaimedAt(null);
        loan.setBookingFailureKind(BookingFailureKind.AMBIGUOUS);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_BOOKING, loan)).as("may have paid").isFalse();
        loan.setBookingFailureKind(null);
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_BOOKING, loan)).as("paid").isFalse();
        loan.setDisbursementStatus(null);
        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_BOOKING, loan)).as("booked").isFalse();
        loan.setLoanAccountStatus(LoanAccountStatus.PENDING);
        loan.setInternalApprovalStatus(InternalApprovalStatus.REJECTED);
        assertThat(CheckpointQueue.atHoldPoint(HoldPoint.BEFORE_BOOKING, loan)).as("declined").isFalse();
    }

    @Test
    @DisplayName("a wait begins when the loan reached the point, or when the checkpoint became active if later")
    void enteredAt() {
        WorkflowStage stage = WorkflowFixtures.checkpoint("PAYOUT_CHECK", HoldPoint.BEFORE_BOOKING);

        assertThat(CheckpointQueue.enteredAt(stage, approvedForBooking(1, ACTIVE_SINCE.minusDays(1))))
                .as("approved before the checkpoint existed").isEqualTo(ACTIVE_SINCE);
        assertThat(CheckpointQueue.enteredAt(stage, approvedForBooking(2, ACTIVE_SINCE.plusHours(2))))
                .isEqualTo(ACTIVE_SINCE.plusHours(2));
        assertThat(CheckpointQueue.enteredAt(stage, approvedForBooking(3, null))).isEqualTo(ACTIVE_SINCE);
    }

    @Test
    @DisplayName("the queue is the loans at the point it applies to and has not decided, oldest wait first")
    void waiting() {
        WorkflowStage stage = WorkflowFixtures.checkpoint("PAYOUT_CHECK", HoldPoint.BEFORE_BOOKING);
        stage.setMinimumPrincipal(new BigDecimal("2000.00"));
        Loan late = approvedForBooking(1, ACTIVE_SINCE.plusHours(3));
        Loan early = approvedForBooking(2, ACTIVE_SINCE.minusHours(1));
        Loan small = approvedForBooking(3, ACTIVE_SINCE);
        small.setPrincipal(new BigDecimal("1999.99"));
        Loan decided = approvedForBooking(4, ACTIVE_SINCE);
        when(loanRepository.findBeforeBooking()).thenReturn(List.of(late, early, small, decided));
        when(decisions.findByStageCodeInAndLoanIdIn(eq(List.of("PAYOUT_CHECK")), anyCollection())).thenReturn(List.of(
                CheckpointDecision.builder().stageCode("PAYOUT_CHECK").loanId(4L).build()));

        assertThat(queue(stage).waiting()).extracting(wait -> wait.loan().getId(), StageQueue.Waiting::enteredAt)
                .containsExactly(tuple(2L, ACTIVE_SINCE),
                        tuple(1L, ACTIVE_SINCE.plusHours(3)));
    }

    @Test
    @DisplayName("an inactive checkpoint holds nothing")
    void inactive() {
        WorkflowStage stage = WorkflowFixtures.checkpoint("PAYOUT_CHECK", HoldPoint.BEFORE_BOOKING);
        stage.setActive(false);
        Loan loan = approvedForBooking(1, ACTIVE_SINCE);
        when(loanRepository.findBeforeBooking()).thenReturn(List.of(loan));

        assertThat(queue(stage).waiting()).isEmpty();
        assertThat(queue(stage).enteredAt(loan)).isEmpty();
    }

    @Test
    @DisplayName("one loan's wait: at the point, applying and undecided")
    void oneLoan() {
        WorkflowStage stage = WorkflowFixtures.checkpoint("PAYOUT_CHECK", HoldPoint.BEFORE_BOOKING);
        stage.setChannels(Set.of("superapp"));
        Loan loan = approvedForBooking(1, ACTIVE_SINCE.plusHours(1));
        Channel superapp = new Channel();
        superapp.setChannelId("superapp");
        loan.setChannel(superapp);

        assertThat(queue(stage).enteredAt(loan)).contains(ACTIVE_SINCE.plusHours(1));
        when(decisions.existsByStageCodeAndLoanId("PAYOUT_CHECK", 1L)).thenReturn(true);
        assertThat(queue(stage).enteredAt(loan)).as("decided").isEmpty();
        when(decisions.existsByStageCodeAndLoanId("PAYOUT_CHECK", 1L)).thenReturn(false);
        loan.setChannel(null);
        assertThat(queue(stage).enteredAt(loan)).as("a portal application").isEmpty();
    }

    @Test
    @DisplayName("the waits that ended are its decisions, timed from when each wait began")
    void ended() {
        WorkflowStage stage = WorkflowFixtures.checkpoint("PAYOUT_CHECK", HoldPoint.BEFORE_LODGEMENT);
        when(decisions.findByStageCodeAndDecidedAtBetweenOrderByIdAsc(eq("PAYOUT_CHECK"), any(), any())).thenReturn(
                List.of(CheckpointDecision.builder().stageCode("PAYOUT_CHECK").loanId(7L).enteredAt(ACTIVE_SINCE)
                        .decidedAt(ACTIVE_SINCE.plusHours(2)).outcome(CheckpointOutcome.DECLINED).build()));

        assertThat(queue(stage).endedBetween(ACTIVE_SINCE, ACTIVE_SINCE.plusDays(1))).containsExactly(
                new StageQueue.Visit(7L, ACTIVE_SINCE, ACTIVE_SINCE.plusHours(2), "DECLINED"));
    }

    @Test
    @DisplayName("each point reads its own loans")
    void pointsReadTheirLoans() {
        Loan fresh = WorkflowFixtures.loan(1, "tmoyo");
        fresh.setLoanApprovalStatus(LoanApprovalStatus.NEW);
        fresh.setCreatedDate(ACTIVE_SINCE.plusMinutes(5));
        Loan accepted = WorkflowFixtures.loan(2, "tmoyo");
        accepted.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        accepted.setInternalApprovalStatus(InternalApprovalStatus.PENDING);
        accepted.setDateApproved(ACTIVE_SINCE.plusMinutes(6));
        when(loanRepository.findBeforeLodgement()).thenReturn(List.of(fresh));
        when(loanRepository.findBeforeCreditApproval()).thenReturn(List.of(accepted));

        assertThat(queue(WorkflowFixtures.checkpoint("A", HoldPoint.BEFORE_LODGEMENT)).waiting())
                .extracting(StageQueue.Waiting::enteredAt).containsExactly(ACTIVE_SINCE.plusMinutes(5));
        assertThat(queue(WorkflowFixtures.checkpoint("B", HoldPoint.BEFORE_CREDIT_APPROVAL)).waiting())
                .extracting(StageQueue.Waiting::enteredAt).containsExactly(ACTIVE_SINCE.plusMinutes(6));
    }
}
