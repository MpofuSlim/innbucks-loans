package zw.co.innbucks.loans.core.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one-word stage the applicant and the originator see (FR-SSB-016). It is derived from the stage
 * statuses, so these pin that each combination reads the way the applicant was told.
 */
class LoanStageTest {

    private static Loan loan(LoanApprovalStatus ssb, InternalApprovalStatus credit) {
        return Loan.builder().loanApprovalStatus(ssb).internalApprovalStatus(credit).build();
    }

    @Test
    @DisplayName("a new application, and one waiting to be lodged, has been received")
    void newIsReceived() {
        assertThat(LoanStage.of(loan(LoanApprovalStatus.NEW, null))).isEqualTo(LoanStage.RECEIVED);
        assertThat(LoanStage.of(loan(null, null))).isEqualTo(LoanStage.RECEIVED);
    }

    @Test
    @DisplayName("a payslip review hold reads as received: the originator is not told of a fraud check")
    void payslipHoldIsReceived() {
        Loan held = loan(LoanApprovalStatus.NEW, null);
        held.setPayslipReviewStatus(PayslipReviewStatus.PENDING);

        assertThat(LoanStage.of(held)).isEqualTo(LoanStage.RECEIVED);
    }

    @Test
    @DisplayName("lodged with SSB and not yet answered is with SSB")
    void processingIsWithSsb() {
        assertThat(LoanStage.of(loan(LoanApprovalStatus.PROCESSING, null))).isEqualTo(LoanStage.WITH_SSB);
    }

    @Test
    @DisplayName("SSB confirmed and Credit undecided is with Credit")
    void ssbApprovedIsWithCredit() {
        assertThat(LoanStage.of(loan(LoanApprovalStatus.APPROVED, null))).isEqualTo(LoanStage.WITH_CREDIT);
        assertThat(LoanStage.of(loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.PENDING)))
                .isEqualTo(LoanStage.WITH_CREDIT);
        assertThat(LoanStage.of(loan(LoanApprovalStatus.PAID, InternalApprovalStatus.PENDING)))
                .isEqualTo(LoanStage.WITH_CREDIT);
    }

    @Test
    @DisplayName("returned by Credit needs more information")
    void returnedNeedsMoreInformation() {
        assertThat(LoanStage.of(loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.RETURNED)))
                .isEqualTo(LoanStage.MORE_INFORMATION_NEEDED);
    }

    @Test
    @DisplayName("approved by Credit and not yet paid is approved")
    void creditApprovedIsApproved() {
        Loan loan = loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED);
        loan.setLoanAccountStatus(LoanAccountStatus.PENDING);

        assertThat(LoanStage.of(loan)).isEqualTo(LoanStage.APPROVED);
    }

    @Test
    @DisplayName("a payout that went through is paid")
    void successfulPayoutIsPaid() {
        Loan loan = loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED);
        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);

        assertThat(LoanStage.of(loan)).isEqualTo(LoanStage.PAID);
    }

    @Test
    @DisplayName("a refused booking or a failed payout on an approved loan is a delayed payout")
    void failedBookingOrPayoutIsDelayed() {
        Loan refused = loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED);
        refused.setLoanAccountStatus(LoanAccountStatus.FAILED);
        Loan failed = loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.APPROVED);
        failed.setLoanAccountStatus(LoanAccountStatus.CREATED);
        failed.setDisbursementStatus(LoanDisbursementStatus.FAILED);

        assertThat(LoanStage.of(refused)).isEqualTo(LoanStage.PAYOUT_DELAYED);
        assertThat(LoanStage.of(failed)).isEqualTo(LoanStage.PAYOUT_DELAYED);
    }

    @Test
    @DisplayName("declined by SSB or by Credit is declined, whatever else is recorded")
    void rejectionIsDeclined() {
        Loan byCredit = loan(LoanApprovalStatus.APPROVED, InternalApprovalStatus.REJECTED);
        byCredit.setLoanAccountStatus(LoanAccountStatus.FAILED);

        assertThat(LoanStage.of(loan(LoanApprovalStatus.REJECTED, null))).isEqualTo(LoanStage.DECLINED);
        assertThat(LoanStage.of(byCredit)).isEqualTo(LoanStage.DECLINED);
    }

    @Test
    @DisplayName("a payslip review that confirms fraud is declined, as the applicant is told")
    void confirmedPayslipReviewIsDeclined() {
        Loan loan = loan(LoanApprovalStatus.NEW, InternalApprovalStatus.REJECTED);
        loan.setPayslipReviewStatus(PayslipReviewStatus.CONFIRMED);

        assertThat(LoanStage.of(loan)).isEqualTo(LoanStage.DECLINED);
    }

    @Test
    @DisplayName("a lodgement that never reached SSB, or that SSB refused, is not completed")
    void failedLodgementIsNotCompleted() {
        assertThat(LoanStage.of(loan(LoanApprovalStatus.FAILED, null))).isEqualTo(LoanStage.NOT_COMPLETED);
    }

    @Test
    @DisplayName("money that reached the applicant is paid, even on a loan later flagged")
    void paidWinsOverEverythingElse() {
        Loan loan = loan(LoanApprovalStatus.REJECTED, InternalApprovalStatus.APPROVED);
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);

        assertThat(LoanStage.of(loan)).isEqualTo(LoanStage.PAID);
    }
}
