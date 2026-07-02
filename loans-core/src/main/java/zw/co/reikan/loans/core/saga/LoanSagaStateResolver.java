package zw.co.reikan.loans.core.saga;

import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;

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
        LoanApprovalStatus approval = loan.getLoanApprovalStatus();
        InternalApprovalStatus internal = loan.getInternalApprovalStatus();
        LoanDisbursementStatus disbursement = loan.getDisbursementStatus();
        LoanAccountStatus account = loan.getLoanAccountStatus();

        // Money outcomes trump everything.
        if (disbursement == LoanDisbursementStatus.SUCCESS) {
            return LoanSagaState.DISBURSED;
        }
        if (disbursement == LoanDisbursementStatus.FAILED) {
            // The retry engine only marks FAILED once attempts are exhausted.
            return LoanSagaState.DISBURSEMENT_FAILED;
        }

        // SSB verification outcomes.
        if (approval == LoanApprovalStatus.REJECTED) {
            return LoanSagaState.SSB_REJECTED;
        }
        if (approval == LoanApprovalStatus.FAILED) {
            return LoanSagaState.SSB_VERIFICATION_FAILED;
        }
        if (approval == null || approval == LoanApprovalStatus.NEW) {
            return LoanSagaState.SSB_VERIFICATION_PENDING;
        }

        // SSB verified (PROCESSING / APPROVED / PAID) — credit assessment phase.
        if (internal == InternalApprovalStatus.REJECTED) {
            return LoanSagaState.CREDIT_REJECTED;
        }
        if (internal == null || internal == InternalApprovalStatus.PENDING) {
            return LoanSagaState.CREDIT_ASSESSMENT_PENDING;
        }

        // Credit approved — disbursement framework phase.
        return account == LoanAccountStatus.CREATED
                ? LoanSagaState.DISBURSEMENT_PENDING
                : LoanSagaState.CREDIT_APPROVED;
    }
}
