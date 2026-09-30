package zw.co.innbucks.loans.core.workflow;

import java.util.Arrays;
import java.util.Optional;

/**
 * The stages an SSB application waits at for a person (FR-SSB-014). Each is a control enforced in code where the
 * loan's state changes, so the set is fixed; what is configurable about each (who works it, its service level and
 * escalation, its assignment) is in {@link WorkflowStage}.
 */
public enum SystemStage {

    /** Held for a payslip finding until an officer clears or confirms it (FR-SSB-007). */
    PAYSLIP_REVIEW(true, true),
    /** SSB has accepted the deduction; Credit approves, rejects or returns it (FR-SSB-015). */
    CREDIT_DECISION(true, true),
    /** Returned by Credit; its originator answers. Worked by the originator, so never assigned. */
    MORE_INFORMATION(false, false),
    /** Held or opened for review by an employment event (FR-SSB-024). */
    EMPLOYMENT_EVENT_REVIEW(true, true),
    /** A lodged deduction for a loan that will not be paid, to cancel with SSB and record (FR-SSB-023). */
    DEDUCTION_CANCELLATION(true, false);

    private final boolean assignable;
    private final boolean segregated;

    SystemStage(boolean assignable, boolean segregated) {
        this.assignable = assignable;
        this.segregated = segregated;
    }

    /** Whether its items can be given to a person; a stage worked by the loan's own originator cannot. */
    public boolean assignable() {
        return assignable;
    }

    /** Whether its decision is barred to whoever originated the loan or is a party to it (FR-PBL-029). */
    public boolean segregated() {
        return segregated;
    }

    public static Optional<SystemStage> of(String code) {
        return Arrays.stream(values()).filter(stage -> stage.name().equals(code)).findFirst();
    }
}
