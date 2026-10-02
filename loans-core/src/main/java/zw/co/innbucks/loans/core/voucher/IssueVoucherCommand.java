package zw.co.innbucks.loans.core.voucher;

import java.math.BigDecimal;

/**
 * What a disbursement hands over to have its voucher issued (FR-SGL-033): the payout it is, the loan, who it is for,
 * and how much. Issued by the disbursement itself, never from a screen.
 *
 * @param disbursementReference the payout's own reference: one voucher per disbursement, however often this is retried
 * @param merchantId            the merchant paid, whose tills alone may take the voucher: the loan's own
 * @param staffMemberId         the staff member, for a Staff Grocery Loan
 * @param customerReference     who the customer is in the product's terms: the employee number for a staff loan
 * @param customerMsisdn        where the voucher is sent: a Zimbabwean mobile number in any written form
 * @param faceValue             what was disbursed, in major units
 */
public record IssueVoucherCommand(VoucherProduct product, String disbursementReference, String loanAccount,
                                  Long merchantId, Long staffMemberId, String customerReference, String customerName,
                                  String customerMsisdn, BigDecimal faceValue, String currency) {
}
