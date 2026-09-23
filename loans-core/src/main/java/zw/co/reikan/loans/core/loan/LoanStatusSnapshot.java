package zw.co.reikan.loans.core.loan;

import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

/**
 * One loan's four status columns — all the duplicate-application check needs,
 * read without the loan's documents and images — and THE definition of an
 * application that is still in flight.
 *
 * <p>In flight means no terminal outcome yet on the path the jobs drive a loan
 * along:</p>
 * <ul>
 *   <li>{@code NEW} (waiting for LoanApprovalServiceJob to lodge it) or
 *       {@code PROCESSING} (lodged with Ndasenda, awaiting its deduction
 *       response);</li>
 *   <li>Ndasenda {@code APPROVED}, credit decision still {@code PENDING} or
 *       unset;</li>
 *   <li>credit {@code APPROVED}, the InnBucks loan account and disbursement
 *       still under way (account not {@code FAILED}, disbursement neither
 *       {@code SUCCESS} nor {@code FAILED}).</li>
 * </ul>
 *
 * <p>Not in flight: SSB {@code REJECTED} or {@code FAILED}, credit
 * {@code REJECTED}, and any loan whose money outcome is decided — disbursement
 * {@code SUCCESS} or {@code FAILED}, which trumps the approval columns as it
 * does in LoanSagaStateResolver. So a disbursed loan does not block a new
 * application; whether a second loan against the same salary is allowed while
 * one is being repaid is a separate business rule, not decided here.</p>
 */
public record LoanStatusSnapshot(Long id,
                                 LoanApprovalStatus loanApprovalStatus,
                                 InternalApprovalStatus internalApprovalStatus,
                                 LoanAccountStatus loanAccountStatus,
                                 LoanDisbursementStatus disbursementStatus) {

    public boolean isInFlight() {
        if (disbursementStatus == LoanDisbursementStatus.SUCCESS
                || disbursementStatus == LoanDisbursementStatus.FAILED
                || loanAccountStatus == LoanAccountStatus.FAILED) {
            return false;
        }
        if (loanApprovalStatus == LoanApprovalStatus.NEW || loanApprovalStatus == LoanApprovalStatus.PROCESSING) {
            return true;
        }
        // Covers both SSB-approved phases: credit still to decide, and credit
        // approved with the account/disbursement still running (checked above).
        return loanApprovalStatus == LoanApprovalStatus.APPROVED
                && internalApprovalStatus != InternalApprovalStatus.REJECTED;
    }
}
