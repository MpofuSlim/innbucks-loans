package zw.co.reikan.loans.core.saga;

import org.junit.jupiter.api.Test;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static zw.co.reikan.loans.core.saga.LoanSagaState.*;

class LoanSagaStateResolverTest {

    private Loan loan(LoanApprovalStatus approval, InternalApprovalStatus internal,
                      LoanAccountStatus account, LoanDisbursementStatus disbursement) {
        Loan loan = new Loan();
        loan.setLoanApprovalStatus(approval);
        loan.setInternalApprovalStatus(internal);
        loan.setLoanAccountStatus(account);
        loan.setDisbursementStatus(disbursement);
        return loan;
    }

    @Test
    void newApplication_isSsbVerificationPending() {
        assertEquals(SSB_VERIFICATION_PENDING,
                LoanSagaStateResolver.resolve(loan(LoanApprovalStatus.NEW, null, null, null)));
    }

    @Test
    void ssbOutcomes() {
        assertEquals(SSB_REJECTED,
                LoanSagaStateResolver.resolve(loan(LoanApprovalStatus.REJECTED, null, null, null)));
        assertEquals(SSB_VERIFICATION_FAILED,
                LoanSagaStateResolver.resolve(loan(LoanApprovalStatus.FAILED, null, null, null)));
    }

    @Test
    void ssbVerified_flowsIntoCreditAssessment() {
        assertEquals(CREDIT_ASSESSMENT_PENDING, LoanSagaStateResolver.resolve(
                loan(LoanApprovalStatus.PROCESSING, InternalApprovalStatus.PENDING, null, null)));
        assertEquals(CREDIT_REJECTED, LoanSagaStateResolver.resolve(
                loan(LoanApprovalStatus.PROCESSING, InternalApprovalStatus.REJECTED, null, null)));
    }

    @Test
    void creditApproved_thenAccountCreated_isDisbursementPending() {
        assertEquals(CREDIT_APPROVED, LoanSagaStateResolver.resolve(
                loan(LoanApprovalStatus.PROCESSING, InternalApprovalStatus.APPROVED,
                        LoanAccountStatus.PENDING, null)));
        assertEquals(DISBURSEMENT_PENDING, LoanSagaStateResolver.resolve(
                loan(LoanApprovalStatus.PROCESSING, InternalApprovalStatus.APPROVED,
                        LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING)));
    }

    @Test
    void moneyOutcomesTrumpEverything() {
        assertEquals(DISBURSED, LoanSagaStateResolver.resolve(
                loan(LoanApprovalStatus.PROCESSING, InternalApprovalStatus.APPROVED,
                        LoanAccountStatus.CREATED, LoanDisbursementStatus.SUCCESS)));
        assertEquals(DISBURSEMENT_FAILED, LoanSagaStateResolver.resolve(
                loan(LoanApprovalStatus.PROCESSING, InternalApprovalStatus.APPROVED,
                        LoanAccountStatus.CREATED, LoanDisbursementStatus.FAILED)));
    }

    @Test
    void stateMachine_terminalStatesNeverAdvance() {
        for (LoanSagaState terminal : new LoanSagaState[]{SSB_REJECTED, SSB_VERIFICATION_FAILED,
                CREDIT_REJECTED, DISBURSED, COMPENSATED}) {
            assertTrue(terminal.isTerminal(), terminal + " should be terminal");
            for (LoanSagaState next : LoanSagaState.values()) {
                assertFalse(terminal.canTransitionTo(next),
                        terminal + " must not transition to " + next);
            }
        }
    }

    @Test
    void stateMachine_compensationPathIsExplicit() {
        assertTrue(DISBURSEMENT_PENDING.canTransitionTo(DISBURSEMENT_FAILED));
        assertTrue(DISBURSEMENT_FAILED.canTransitionTo(COMPENSATED));
        assertFalse(DISBURSEMENT_FAILED.canTransitionTo(DISBURSED)); // no resurrection after failure
    }

    @Test
    void stateMachine_happyPathIsLegal() {
        assertTrue(SSB_VERIFICATION_PENDING.canTransitionTo(CREDIT_ASSESSMENT_PENDING));
        assertTrue(CREDIT_ASSESSMENT_PENDING.canTransitionTo(CREDIT_APPROVED));
        assertTrue(CREDIT_APPROVED.canTransitionTo(DISBURSEMENT_PENDING));
        assertTrue(DISBURSEMENT_PENDING.canTransitionTo(DISBURSED));
    }
}
