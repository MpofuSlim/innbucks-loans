package zw.co.innbucks.loans.core.staff.loan;

/** A Staff Grocery Loan with the agreement it was accepted under. */
public record StaffLoanDetailResponse(StaffLoanResponse loan, StaffLoanAgreementResponse agreement) {
}
