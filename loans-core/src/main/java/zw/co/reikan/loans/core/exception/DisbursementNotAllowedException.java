package zw.co.reikan.loans.core.exception;

/**
 * A manual payout the loan's current state does not allow — rendered as a 409. The
 * request is well-formed; paying this loan now could pay it twice, or pay a loan that
 * was never approved. The message names the reason and what an operator should do.
 */
public class DisbursementNotAllowedException extends RuntimeException {

    public DisbursementNotAllowedException(String message) {
        super(message);
    }
}
