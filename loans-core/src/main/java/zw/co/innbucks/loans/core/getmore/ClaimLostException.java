package zw.co.innbucks.loans.core.getmore;

/**
 * {@link GenerateGuard#beforeGenerate()} found the issuance no longer claimed by this attempt (another attempt took a
 * stale claim over, or the loan was cancelled): nothing was sent, and this attempt stops where it is.
 */
public class ClaimLostException extends RuntimeException {

    public ClaimLostException(String message) {
        super(message);
    }
}
