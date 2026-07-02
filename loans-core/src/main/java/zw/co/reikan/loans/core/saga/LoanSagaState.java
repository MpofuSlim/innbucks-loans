package zw.co.reikan.loans.core.saga;

import java.util.Map;
import java.util.Set;

/**
 * Distributed-transaction states for the loan lifecycle saga:
 *
 * <pre>
 * SSB_VERIFICATION_PENDING ──► CREDIT_ASSESSMENT_PENDING ──► CREDIT_APPROVED
 *          │                            │                          │
 *          ├─► SSB_REJECTED (T)         └─► CREDIT_REJECTED (T)    ▼
 *          └─► SSB_VERIFICATION_FAILED (T)              DISBURSEMENT_PENDING
 *                                                            │         │
 *                                                            ▼         ▼
 *                                                     DISBURSED (T)  DISBURSEMENT_FAILED
 *                                                                       │ (compensation)
 *                                                                       ▼
 *                                                                  COMPENSATED (T)
 * </pre>
 *
 * (T) = terminal. Terminal states never regress.
 */
public enum LoanSagaState {

    /** Application received; awaiting Ndasenda (SSB payroll bureau) verification. */
    SSB_VERIFICATION_PENDING,
    /** Bureau rejected the deduction request. Terminal. */
    SSB_REJECTED,
    /** Bureau call failed permanently (network/system). Terminal — operator queue. */
    SSB_VERIFICATION_FAILED,
    /** SSB accepted; internal credit assessment in progress. */
    CREDIT_ASSESSMENT_PENDING,
    /** Credit declined internally. Terminal. */
    CREDIT_REJECTED,
    /** Credit approved; loan account being provisioned on the disbursement rail. */
    CREDIT_APPROVED,
    /** Account live; funds push scheduled / retrying with backoff. */
    DISBURSEMENT_PENDING,
    /** Funds confirmed with the customer. Terminal — ledger posted. */
    DISBURSED,
    /** Disbursement permanently failed — compensation must run. */
    DISBURSEMENT_FAILED,
    /** Compensation completed; books consistent. Terminal. */
    COMPENSATED;

    private static final Set<LoanSagaState> TERMINAL =
            Set.of(SSB_REJECTED, SSB_VERIFICATION_FAILED, CREDIT_REJECTED, DISBURSED, COMPENSATED);

    /** Legal forward/compensation edges — anything else observed is an anomaly. */
    private static final Map<LoanSagaState, Set<LoanSagaState>> ALLOWED = Map.of(
            SSB_VERIFICATION_PENDING, Set.of(CREDIT_ASSESSMENT_PENDING, SSB_REJECTED, SSB_VERIFICATION_FAILED,
                    // fast-path: bureau + credit can resolve between two reconciliation ticks
                    CREDIT_APPROVED, CREDIT_REJECTED, DISBURSEMENT_PENDING, DISBURSED, DISBURSEMENT_FAILED),
            CREDIT_ASSESSMENT_PENDING, Set.of(CREDIT_APPROVED, CREDIT_REJECTED,
                    DISBURSEMENT_PENDING, DISBURSED, DISBURSEMENT_FAILED),
            CREDIT_APPROVED, Set.of(DISBURSEMENT_PENDING, DISBURSED, DISBURSEMENT_FAILED),
            DISBURSEMENT_PENDING, Set.of(DISBURSED, DISBURSEMENT_FAILED),
            DISBURSEMENT_FAILED, Set.of(COMPENSATED)
    );

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean canTransitionTo(LoanSagaState next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }
}
