package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.exception.ConflictException;

/**
 * An attempt is issuing the loan's GetMore voucher right now: it holds the issuance's claim and may be calling GetMore.
 * Cancelling the loan, or asking for another attempt, has to wait for it (a 409 the caller simply repeats a minute
 * later): stopping the loan under a call that may be minting its coupon would leave that coupon live.
 */
public class IssuanceInProgressException extends ConflictException {

    public IssuanceInProgressException(String loanReference) {
        super("A voucher for " + loanReference + " is being issued right now; try again in a minute.");
    }
}
