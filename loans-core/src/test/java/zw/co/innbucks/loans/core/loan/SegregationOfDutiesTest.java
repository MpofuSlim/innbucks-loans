package zw.co.innbucks.loans.core.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Who approved a loan, for the checks made after the approval (FR-SSB-018). */
class SegregationOfDutiesTest {

    @Test
    @DisplayName("the approver of an approved loan, however their name is cased")
    void approverOfAnApprovedLoan() {
        Loan loan = decided(InternalApprovalStatus.APPROVED, "cmanager");

        assertThat(SegregationOfDuties.approved(loan, "cmanager")).isTrue();
        assertThat(SegregationOfDuties.approved(loan, "CManager")).isTrue();
        assertThat(SegregationOfDuties.approved(loan, "finance1")).isFalse();
        assertThat(SegregationOfDuties.approved(loan, null)).isFalse();
    }

    @Test
    @DisplayName("nobody approved a loan that was returned, rejected or not yet decided")
    void noApproverUnlessApproved() {
        assertThat(SegregationOfDuties.approved(decided(InternalApprovalStatus.RETURNED, "cmanager"), "cmanager"))
                .isFalse();
        assertThat(SegregationOfDuties.approved(decided(InternalApprovalStatus.REJECTED, "cmanager"), "cmanager"))
                .isFalse();
        assertThat(SegregationOfDuties.approved(decided(InternalApprovalStatus.PENDING, null), "cmanager")).isFalse();
        assertThat(SegregationOfDuties.approved(decided(InternalApprovalStatus.APPROVED, null), null)).isFalse();
    }

    private static Loan decided(InternalApprovalStatus status, String by) {
        Loan loan = new Loan();
        loan.setInternalApprovalStatus(status);
        loan.setInternalApprovalBy(by);
        return loan;
    }
}
