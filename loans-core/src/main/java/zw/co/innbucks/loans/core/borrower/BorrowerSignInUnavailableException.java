package zw.co.innbucks.loans.core.borrower;

/**
 * No SuperApp borrower can sign in because this server trusts no middleware key (BORROWER_ASSERTION_PUBLIC_KEY), and
 * staging's test assertions are off. A 503: the assertion was not even looked at, and nothing is wrong with it.
 */
public class BorrowerSignInUnavailableException extends RuntimeException {

    public BorrowerSignInUnavailableException() {
        super("SuperApp sign-in to the Staff Grocery Loan is not available on this server");
    }
}
