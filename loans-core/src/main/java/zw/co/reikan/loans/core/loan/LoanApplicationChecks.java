package zw.co.reikan.loans.core.loan;

/**
 * Bean-validation group for the fields a loan APPLICATION needs on top of a
 * quote — everything the InnBucks pre-approved application
 * ({@code InnbucksServiceImpl.createLoanAccount}) sends or dereferences.
 *
 * <p>A separate group because {@code POST /api/loans/calculate} takes the same
 * {@link LoanRequest} and must keep quoting from the {@code Default} group
 * alone; only an application is held to this set.
 *
 * <p>Why these are enforced up front: without them the application was
 * accepted, sent for payroll approval and credit sign-off, and only failed —
 * or threw a NullPointerException — at the InnBucks step, after the customer
 * believed they had applied. Enforced on the HTTP body (all errors in one 400)
 * AND in {@code LoanServiceImpl.requestLoan}, because bulk upload calls the
 * service directly.
 */
public interface LoanApplicationChecks {
}
