package zw.co.innbucks.loans.core.turnaround;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.workflow.AssignmentMode;
import zw.co.innbucks.loans.core.workflow.StageKind;
import zw.co.innbucks.loans.core.workflow.WorkItem;
import zw.co.innbucks.loans.core.workflow.WorkflowStage;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** A loan's wait for a credit decision, against the CREDIT_DECISION stage's service level (FR-PBL-030). */
class CreditTurnaroundTest {

    private static final LocalDateTime SSB_APPROVED = LocalDateTime.of(2026, 9, 30, 6, 5, 12);

    private static WorkflowStage stage(Integer escalationHours) {
        return WorkflowStage.builder().code("CREDIT_DECISION").kind(StageKind.SYSTEM).name("Credit decision")
                .assignment(AssignmentMode.OPTIONAL).targetHours(24).escalationHours(escalationHours)
                .updatedBy("system").updatedAt(SSB_APPROVED).build();
    }

    private static Loan awaiting() {
        Loan loan = new Loan();
        loan.setLoanApprovalStatus(LoanApprovalStatus.APPROVED);
        loan.setInternalApprovalStatus(InternalApprovalStatus.PENDING);
        loan.setDateApproved(SSB_APPROVED);
        return loan;
    }

    @Test
    @DisplayName("a wait runs from SSB's approval: due at the target, escalating at the escalation point")
    void measuredFromSsbApproval() {
        CreditTurnaround turnaround = CreditTurnaround.of(awaiting(), stage(48), null, SSB_APPROVED.plusMinutes(58));

        assertThat(turnaround.queueEnteredAt()).isEqualTo(SSB_APPROVED);
        assertThat(turnaround.dueAt()).isEqualTo(SSB_APPROVED.plusHours(24));
        assertThat(turnaround.escalatesAt()).isEqualTo(SSB_APPROVED.plusHours(48));
        assertThat(turnaround.waitingHours()).isEqualByComparingTo("1.0");
        assertThat(turnaround.overdue()).isFalse();
        assertThat(turnaround.escalatedAt()).isNull();
        assertThat(turnaround.assignedTo()).isNull();
    }

    @Test
    @DisplayName("an answered return starts a new wait from the answer")
    void measuredFromTheLastResubmission() {
        Loan loan = awaiting();
        LocalDateTime resubmitted = SSB_APPROVED.plusDays(3);
        loan.setCreditResubmittedAt(resubmitted);

        CreditTurnaround turnaround = CreditTurnaround.of(loan, stage(48), null, resubmitted.plusHours(2));

        assertThat(turnaround.queueEnteredAt()).isEqualTo(resubmitted);
        assertThat(turnaround.waitingHours()).isEqualByComparingTo("2.0");
        assertThat(turnaround.overdue()).isFalse();
    }

    @Test
    @DisplayName("overdue only once the target has passed; the wait's own escalation and assignee are shown")
    void overdueAfterTheTarget() {
        WorkItem item = WorkItem.builder().stageCode("CREDIT_DECISION").enteredAt(SSB_APPROVED)
                .escalatedAt(SSB_APPROVED.plusHours(48)).assignedTo("cmanager").build();

        assertThat(CreditTurnaround.of(awaiting(), stage(48), item, SSB_APPROVED.plusHours(24)).overdue()).isFalse();
        CreditTurnaround late = CreditTurnaround.of(awaiting(), stage(48), item,
                SSB_APPROVED.plusHours(49).plusMinutes(30));
        assertThat(late.overdue()).isTrue();
        assertThat(late.waitingHours()).isEqualByComparingTo("49.5");
        assertThat(late.escalatedAt()).isEqualTo(SSB_APPROVED.plusHours(48));
        assertThat(late.assignedTo()).isEqualTo("cmanager");
    }

    @Test
    @DisplayName("an item from an earlier wait says nothing about this one, and a stage with no escalation has no point")
    void earlierWaitsItemIgnored() {
        Loan loan = awaiting();
        loan.setCreditResubmittedAt(SSB_APPROVED.plusDays(1));
        WorkItem earlier = WorkItem.builder().stageCode("CREDIT_DECISION").enteredAt(SSB_APPROVED)
                .escalatedAt(SSB_APPROVED.plusHours(48)).assignedTo("rnyathi").build();

        CreditTurnaround turnaround = CreditTurnaround.of(loan, stage(null), earlier, SSB_APPROVED.plusDays(2));

        assertThat(turnaround.escalatedAt()).isNull();
        assertThat(turnaround.assignedTo()).isNull();
        assertThat(turnaround.escalatesAt()).isNull();
    }

    @Test
    @DisplayName("only a loan waiting on Credit has a turnaround")
    void onlyWhileWaitingOnCredit() {
        Loan withSsb = awaiting();
        withSsb.setLoanApprovalStatus(LoanApprovalStatus.PROCESSING);
        Loan decided = awaiting();
        decided.setInternalApprovalStatus(InternalApprovalStatus.APPROVED);
        Loan returned = awaiting();
        returned.setInternalApprovalStatus(InternalApprovalStatus.RETURNED);
        Loan noArrival = awaiting();
        noArrival.setDateApproved(null);

        LocalDateTime now = SSB_APPROVED.plusHours(1);
        assertThat(CreditTurnaround.of(withSsb, stage(48), null, now)).isNull();
        assertThat(CreditTurnaround.of(decided, stage(48), null, now)).isNull();
        assertThat(CreditTurnaround.of(returned, stage(48), null, now)).isNull();
        assertThat(CreditTurnaround.of(noArrival, stage(48), null, now)).isNull();
    }
}
