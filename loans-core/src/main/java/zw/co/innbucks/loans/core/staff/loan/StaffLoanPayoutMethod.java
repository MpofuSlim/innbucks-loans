package zw.co.innbucks.loans.core.staff.loan;

/**
 * How a Staff Grocery Loan is paid out, decided when it is accepted (loans.staff-loans.payout.method) and kept on the
 * loan, so changing the setting never changes a loan already accepted.
 */
public enum StaffLoanPayoutMethod {
    /** As before: the loan waits for the voucher disbursement; nothing is sent to the bank. */
    VOUCHER,
    /** The bank books it as a pre-approved loan and pays it into the borrower's own account. */
    CASH
}
