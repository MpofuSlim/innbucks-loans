package zw.co.innbucks.loans.core.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.loan.StaffLoan;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutDestination;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMethod;
import zw.co.innbucks.loans.core.user.User;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who approved a loan, for the checks made after the approval (FR-SSB-018), and who is a party to a Staff Grocery
 * Loan, for the people who pay one out or settle its payout.
 */
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

    @Test
    @DisplayName("a staff loan's borrower is a party to it: by their national ID on the register, or by the loan's"
            + " phone, the wallet it pays or their phone on the register now, however either is written")
    void staffLoanParty() {
        StaffLoan loan = StaffLoan.builder().id(143L).reference("SGL-2026-000143").msisdn("263772123123")
                .payoutMethod(StaffLoanPayoutMethod.CASH).payoutDestination(StaffLoanPayoutDestination.VEENGU_WALLET)
                .payoutAccount("263772123123").build();
        StaffMember borrower = StaffMember.builder().id(2L).nationalId("632345678B42").msisdn("263773456789").build();

        assertThat(SegregationOfDuties.isPartyTo(loan, borrower, user("63-2345678-B-42", null))).isTrue();
        assertThat(SegregationOfDuties.isPartyTo(loan, borrower, user(null, "0772 123 123"))).isTrue();
        assertThat(SegregationOfDuties.isPartyTo(loan, borrower, user(null, "+263773456789"))).as("their phone now")
                .isTrue();
        assertThat(SegregationOfDuties.isPartyTo(loan, borrower, user("63-1111111-A-11", "0771000000"))).isFalse();
        assertThat(SegregationOfDuties.isPartyTo(loan, null, user("63-2345678-B-42", null)))
                .as("no register record: matched on the loan's numbers alone").isFalse();
        assertThat(SegregationOfDuties.isPartyTo(loan, borrower, null)).isFalse();
        assertThat(SegregationOfDuties.isPartyTo(loan, borrower, user(null, null))).isFalse();
    }

    @Test
    @DisplayName("an account payout's account number is never matched against a phone")
    void staffLoanAccountIsNotAPhone() {
        StaffLoan loan = StaffLoan.builder().id(143L).reference("SGL-2026-000143").msisdn("263772123123")
                .payoutMethod(StaffLoanPayoutMethod.CASH).payoutDestination(StaffLoanPayoutDestination.BR_ACCOUNT)
                .payoutAccount("1771000000").build();

        assertThat(SegregationOfDuties.isPartyTo(loan, null, user(null, "0771000000"))).isFalse();
    }

    private static User user(String idNumber, String mobile) {
        User user = new User();
        user.setUsername("finance1");
        user.setIdNumber(idNumber);
        user.setMobileNumber(mobile);
        return user;
    }
}
