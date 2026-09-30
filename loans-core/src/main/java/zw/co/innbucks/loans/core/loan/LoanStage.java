package zw.co.innbucks.loans.core.loan;

import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;

/**
 * Where an application stands, in one word, for the applicant and the officer or agent who captured it
 * (FR-SSB-016). Derived from the four stage statuses (SSB, Credit, booking, payout), so it can never
 * disagree with them. A payslip review hold is deliberately not a stage: telling the originator an
 * application is held for a fraud check would warn exactly the person the check may be about, so a held
 * application reads as RECEIVED.
 */
public enum LoanStage {

    /** Received, and being sent to SSB for the payroll deduction. */
    RECEIVED,
    /** With SSB, waiting for the payroll deduction to be confirmed. */
    WITH_SSB,
    /** SSB confirmed the deduction; Credit is assessing the application. */
    WITH_CREDIT,
    /** Credit asked for more information; the originator answers it. */
    MORE_INFORMATION_NEEDED,
    /** Approved, and being paid out. */
    APPROVED,
    /** Paid out. */
    PAID,
    /** Declined, by SSB or by Credit. */
    DECLINED,
    /** Approved, but the payout did not go through; it is being resolved. */
    PAYOUT_DELAYED,
    /** Could not be sent to SSB, so it went no further. */
    NOT_COMPLETED;

    /** The stage the loan's statuses put it at, the furthest first. */
    public static LoanStage of(Loan loan) {
        LoanApprovalStatus ssb = loan.getLoanApprovalStatus();
        InternalApprovalStatus credit = loan.getInternalApprovalStatus();
        if (loan.getDisbursementStatus() == LoanDisbursementStatus.SUCCESS) {
            return PAID;
        }
        if (ssb == LoanApprovalStatus.REJECTED || credit == InternalApprovalStatus.REJECTED) {
            return DECLINED;
        }
        if (ssb == LoanApprovalStatus.FAILED) {
            return NOT_COMPLETED;
        }
        if (loan.getDisbursementStatus() == LoanDisbursementStatus.FAILED
                || loan.getLoanAccountStatus() == LoanAccountStatus.FAILED) {
            return PAYOUT_DELAYED;
        }
        if (credit == InternalApprovalStatus.APPROVED) {
            return APPROVED;
        }
        if (credit == InternalApprovalStatus.RETURNED) {
            return MORE_INFORMATION_NEEDED;
        }
        if (ssb == LoanApprovalStatus.APPROVED || ssb == LoanApprovalStatus.PAID) {
            return WITH_CREDIT;
        }
        if (ssb == LoanApprovalStatus.PROCESSING) {
            return WITH_SSB;
        }
        return RECEIVED;
    }
}
