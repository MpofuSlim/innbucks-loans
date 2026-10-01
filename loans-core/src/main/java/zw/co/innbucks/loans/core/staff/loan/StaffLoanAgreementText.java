package zw.co.innbucks.loans.core.staff.loan;

/**
 * The agreement filled with the borrower's terms, as it will be accepted (FR-SGL-027). Accepting sends back
 * {@code version} and {@code contentSha256}, so what is recorded is exactly what was shown.
 */
public record StaffLoanAgreementText(int version, String title, String content, String contentSha256) {
}
