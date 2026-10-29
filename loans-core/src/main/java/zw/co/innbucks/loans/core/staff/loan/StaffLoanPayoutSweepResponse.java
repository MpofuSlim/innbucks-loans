package zw.co.innbucks.loans.core.staff.loan;

/**
 * What "Pay all ready" queued: one pass over the cash payouts READY to send, oldest first.
 *
 * @param ready      how many READY payouts the pass was queued for
 * @param skippedOwn how many were left out because the person who asked is a party to them (the borrower, or the
 *                   holder of the wallet paid): someone else must send those
 */
public record StaffLoanPayoutSweepResponse(long ready, long skippedOwn) {
}
