package zw.co.reikan.loans.core.bulk;

/**
 * Outcome of one isolated item inside a bulk run. A failure carries its error
 * context; it never carries down its neighbours.
 */
public record BulkLoanItemOutcome(
        int index,
        boolean success,
        String internalReference,
        String publicReference,
        String error) {

    public static BulkLoanItemOutcome ok(int index, String internalReference, String publicReference) {
        return new BulkLoanItemOutcome(index, true, internalReference, publicReference, null);
    }

    public static BulkLoanItemOutcome failed(int index, String error) {
        return new BulkLoanItemOutcome(index, false, null, null, error);
    }
}
