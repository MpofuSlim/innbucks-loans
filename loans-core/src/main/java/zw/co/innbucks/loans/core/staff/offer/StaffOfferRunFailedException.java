package zw.co.innbucks.loans.core.staff.offer;

/**
 * An offer run broke off. Everything it did was rolled back, and the attempt is recorded as a FAILED run, which this
 * carries, so the caller can say which run to look at; the cause is the error that stopped it.
 */
public class StaffOfferRunFailedException extends RuntimeException {

    private final transient StaffOfferRunResponse run;

    public StaffOfferRunFailedException(StaffOfferRunResponse run, RuntimeException cause) {
        super(String.format("The offer run failed and nothing it did was kept. It is recorded as run %d, with the"
                + " reason; try again, and if it fails again, report run %d", run.id(), run.id()), cause);
        this.run = run;
    }

    public StaffOfferRunResponse run() {
        return run;
    }
}
