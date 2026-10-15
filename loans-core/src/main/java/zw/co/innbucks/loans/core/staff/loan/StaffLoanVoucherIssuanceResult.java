package zw.co.innbucks.loans.core.staff.loan;

/** An operator's attempt at a loan's GetMore voucher: where its issuance stands after it, and the loan. */
public record StaffLoanVoucherIssuanceResult(StaffLoanVoucherIssuanceResponse issuance, StaffLoanResponse loan) {
}
