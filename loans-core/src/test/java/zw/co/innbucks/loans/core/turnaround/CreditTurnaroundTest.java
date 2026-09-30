package zw.co.innbucks.loans.core.turnaround;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** A loan's wait for a credit decision, against the service level (FR-PBL-030). */
class CreditTurnaroundTest {

    private static final LocalDateTime SSB_APPROVED = LocalDateTime.of(2026, 9, 30, 6, 5, 12);
    private static final ServiceLevel LEVEL = ServiceLevel.builder().stage(ServiceLevelStage.CREDIT_DECISION)
            .targetHours(24).escalationHours(48).updatedBy("system").updatedAt(SSB_APPROVED).build();

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
        CreditTurnaround turnaround = CreditTurnaround.of(awaiting(), LEVEL, SSB_APPROVED.plusMinutes(58));

        assertThat(turnaround.queueEnteredAt()).isEqualTo(SSB_APPROVED);
        assertThat(turnaround.dueAt()).isEqualTo(SSB_APPROVED.plusHours(24));
        assertThat(turnaround.escalatesAt()).isEqualTo(SSB_APPROVED.plusHours(48));
        assertThat(turnaround.waitingHours()).isEqualByComparingTo("1.0");
        assertThat(turnaround.overdue()).isFalse();
        assertThat(turnaround.escalatedAt()).isNull();
    }

    @Test
    @DisplayName("an answered return starts a new wait from the answer")
    void measuredFromTheLastResubmission() {
        Loan loan = awaiting();
        LocalDateTime resubmitted = SSB_APPROVED.plusDays(3);
        loan.setCreditResubmittedAt(resubmitted);

        CreditTurnaround turnaround = CreditTurnaround.of(loan, LEVEL, resubmitted.plusHours(2));

        assertThat(turnaround.queueEnteredAt()).isEqualTo(resubmitted);
        assertThat(turnaround.waitingHours()).isEqualByComparingTo("2.0");
        assertThat(turnaround.overdue()).isFalse();
    }

    @Test
    @DisplayName("overdue only once the target has passed, and the escalation is shown")
    void overdueAfterTheTarget() {
        Loan loan = awaiting();
        loan.setCreditEscalatedAt(SSB_APPROVED.plusHours(48));

        assertThat(CreditTurnaround.of(loan, LEVEL, SSB_APPROVED.plusHours(24)).overdue()).isFalse();
        CreditTurnaround late = CreditTurnaround.of(loan, LEVEL, SSB_APPROVED.plusHours(49).plusMinutes(30));
        assertThat(late.overdue()).isTrue();
        assertThat(late.waitingHours()).isEqualByComparingTo("49.5");
        assertThat(late.escalatedAt()).isEqualTo(SSB_APPROVED.plusHours(48));
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
        assertThat(CreditTurnaround.of(withSsb, LEVEL, now)).isNull();
        assertThat(CreditTurnaround.of(decided, LEVEL, now)).isNull();
        assertThat(CreditTurnaround.of(returned, LEVEL, now)).isNull();
        assertThat(CreditTurnaround.of(noArrival, LEVEL, now)).isNull();
    }

    @Test
    @DisplayName("hours are to one decimal and never negative, so a clock a little behind reads as no wait")
    void hoursBetween() {
        assertThat(CreditTurnaround.hoursBetween(SSB_APPROVED, SSB_APPROVED.plusMinutes(97))).isEqualByComparingTo("1.6");
        assertThat(CreditTurnaround.hoursBetween(SSB_APPROVED, SSB_APPROVED.minusMinutes(5)))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
