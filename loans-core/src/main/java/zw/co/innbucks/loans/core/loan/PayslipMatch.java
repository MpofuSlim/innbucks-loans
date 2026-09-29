package zw.co.innbucks.loans.core.loan;

/** An application already on file with a given payslip: enough to say whether it is the same applicant. */
public record PayslipMatch(Long loanId, String ecNumber, String nationalIdNumber) {
}
