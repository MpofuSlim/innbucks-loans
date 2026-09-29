package zw.co.innbucks.loans.core.loan;

/** Names of the pricing rows in the {@code parameter} table that the loan calculator reads. */
public final class LoanParameterNames {

    public static final String COMMISSION_RATE = "commission_rate";
    public static final String ADMIN_FEE_RATE = "admin_fee_rate";
    public static final String MONTHLY_INTEREST_RATE = "monthly_interest_rate";
    public static final String MINIMUM_LOAN_TENOR = "minimum_loan_tenor";
    public static final String MAXIMUM_LOAN_TENOR = "maximum_loan_tenor";
    public static final String MINIMUM_LOAN_AMOUNT = "minimum_loan_amount";
    public static final String MAXIMUM_LOAN_AMOUNT = "maximum_loan_amount";

    private LoanParameterNames() {
    }
}
