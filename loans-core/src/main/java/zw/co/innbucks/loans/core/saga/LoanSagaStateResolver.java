package zw.co.innbucks.loans.core.saga;

import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanStatusSnapshot;

/**
 * Pure projection of the EXISTING loan status columns onto the saga state
 * machine. This is deliberate: the functioning approval/disbursement jobs
 * remain the writers of record — the saga observes and formalises, so the
 * orchestration layer is added without changing how loans work.
 */
public final class LoanSagaStateResolver {

    private LoanSagaStateResolver() {
    }

    public static LoanSagaState resolve(Loan loan) {
        return resolve(loan.getLoanApprovalStatus(), loan.getInternalApprovalStatus(),
                loan.getLoanAccountStatus(), loan.getDisbursementStatus());
    }

    /** From the status columns alone, so the orchestrator can tell which loans moved without loading them. */
    public static LoanSagaState resolve(LoanStatusSnapshot loan) {
        return resolve(loan.loanApprovalStatus(), loan.internalApprovalStatus(),
                loan.loanAccountStatus(), loan.disbursementStatus());
    }

    private static LoanSagaState resolve(LoanApprovalStatus approval, InternalApprovalStatus internal,
                                         LoanAccountStatus account, LoanDisbursementStatus disbursement) {
        // Money outcomes trump everything.
        if (disbursement == LoanDisbursementStatus.SUCCESS) {
            return LoanSagaState.DISBURSED;
        }
        if (disbursement == LoanDisbursementStatus.FAILED) {
            // The retry engine only marks FAILED once attempts are exhausted.
            return LoanSagaState.DISBURSEMENT_FAILED;
        }

        // Rejected at payslip review (FR-SSB-007) before it was ever lodged: never goes to SSB.
        if (internal == InternalApprovalStatus.REJECTED
                && (approval == null || approval == LoanApprovalStatus.NEW)) {
            return LoanSagaState.CREDIT_REJECTED;
        }

        // SSB verification outcomes.
        if (approval == LoanApprovalStatus.REJECTED) {
            return LoanSagaState.SSB_REJECTED;
        }
        if (approval == LoanApprovalStatus.FAILED) {
            return LoanSagaState.SSB_VERIFICATION_FAILED;
        }
        // PROCESSING is lodged and still awaiting Ndasenda's answer, which can yet be a refusal: not
        // verified. Reading it as verified moved the saga to credit assessment, from where Ndasenda's
        // refusal was an illegal transition, so the saga never finished.
        if (approval == null || approval == LoanApprovalStatus.NEW || approval == LoanApprovalStatus.PROCESSING) {
            return LoanSagaState.SSB_VERIFICATION_PENDING;
        }

        // SSB verified (APPROVED / PAID) — credit assessment phase.
        if (internal == InternalApprovalStatus.REJECTED) {
            return LoanSagaState.CREDIT_REJECTED;
        }
        // RETURNED is still Credit's: waiting on the originator's answer, then back in the queue.
        if (internal == null || internal == InternalApprovalStatus.PENDING
                || internal == InternalApprovalStatus.RETURNED) {
            return LoanSagaState.CREDIT_ASSESSMENT_PENDING;
        }

        // Credit approved — disbursement framework phase.
        return account == LoanAccountStatus.CREATED
                ? LoanSagaState.DISBURSEMENT_PENDING
                : LoanSagaState.CREDIT_APPROVED;
    }
}
