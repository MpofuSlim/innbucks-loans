package zw.co.innbucks.loans.core.employment;

/**
 * Something that happened to a borrower's employment in the civil service (FR-SSB-024). Each type has a
 * treatment, configured in {@link EmploymentEventTreatment}, that decides what the event does to the borrower's
 * applications and loans.
 */
public enum EmploymentEventType {

    /** Moved to another ministry. Names the new ministry. */
    TRANSFER(true, false, false),
    /** Seconded away from their post, perhaps off the SSB payroll. Names where; may end on a known date. */
    SECONDMENT(true, false, true),
    /** Promoted, or moved to another notch. Names the new grade. */
    PROMOTION(false, true, false),
    /** Suspended from duty, on reduced or no pay. May end on a known date. */
    SUSPENSION(false, false, true),
    /** On unpaid leave. May end on a known date. */
    UNPAID_LEAVE(false, false, true),
    /** Resigned from the service. */
    RESIGNATION(false, false, false),
    /** Retired from the service. */
    RETIREMENT(false, false, false),
    /** Died in service. */
    DEATH_IN_SERVICE(false, false, false);

    private final boolean requiresMinistry;
    private final boolean requiresGrade;
    private final boolean mayEnd;

    EmploymentEventType(boolean requiresMinistry, boolean requiresGrade, boolean mayEnd) {
        this.requiresMinistry = requiresMinistry;
        this.requiresGrade = requiresGrade;
        this.mayEnd = mayEnd;
    }

    /** A transfer or secondment names the ministry the borrower moves to. */
    public boolean requiresMinistry() {
        return requiresMinistry;
    }

    /** A promotion names the new grade or notch. */
    public boolean requiresGrade() {
        return requiresGrade;
    }

    /** Whether it can end on a date, so an end date means something. */
    public boolean mayEnd() {
        return mayEnd;
    }
}
