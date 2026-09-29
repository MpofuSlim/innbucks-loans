package zw.co.innbucks.loans.core.loan;

/**
 * Bean-validation group for the fields a loan APPLICATION needs on top of a
 * quote — everything the InnBucks pre-approved application
 * ({@code InnbucksDisbursementService.createLoanAccount}) sends or dereferences.
 *
 * <p>A separate group so the {@code Default} group stays what every
 * {@link LoanApplicationRequest} needs whatever else it carries; only an
 * application is held to this set. A quote takes {@link LoanQuoteRequest} instead.
 *
 * <p>Why these are enforced up front: without them the application was
 * accepted, sent for payroll approval and credit sign-off, and only failed —
 * or threw a NullPointerException — at the InnBucks step, after the customer
 * believed they had applied. Enforced on the HTTP body (all errors in one 400)
 * AND in {@code LoanServiceImpl.requestLoan}, for any caller that reaches the
 * service directly.
 */
public interface LoanApplicationChecks {
}
