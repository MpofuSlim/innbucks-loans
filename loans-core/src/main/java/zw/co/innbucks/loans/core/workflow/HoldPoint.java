package zw.co.innbucks.loans.core.workflow;

/**
 * Where in an SSB loan's life a checkpoint stage holds it (FR-SSB-014). Each is a point the pipeline already stops a
 * loan at for its own holds (a payslip review, an employment event), so a checkpoint adds a condition to an existing
 * gate rather than a new path through the pipeline.
 */
public enum HoldPoint {

    /** The deduction is not lodged with SSB until the checkpoint is cleared. */
    BEFORE_LODGEMENT(15),
    /** Credit cannot approve the application until the checkpoint is cleared; it can still reject or return it. */
    BEFORE_CREDIT_APPROVAL(25),
    /** The loan is not booked with InnBucks, which pays it out, until the checkpoint is cleared. */
    BEFORE_BOOKING(35);

    private final int displayOrder;

    HoldPoint(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    /** Where a checkpoint here sits among the system stages unless one is given: after the stage it follows. */
    public int displayOrder() {
        return displayOrder;
    }
}
