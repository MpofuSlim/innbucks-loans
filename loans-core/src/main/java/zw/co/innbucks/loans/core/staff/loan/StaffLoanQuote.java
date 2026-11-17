package zw.co.innbucks.loans.core.staff.loan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything the borrower must see before accepting (FR-SGL-026), and the agreement to accept (FR-SGL-027). The
 * sentences are the app's to show as they are: {@code collection} how it is repaid, {@code redemption} how they get
 * the money (where the voucher can be spent, or for a loan paid in cash, the account it is paid into), and
 * {@code unredeemedVoucher} what happens when a voucher is not spent in full (OQ-09).
 *
 * <p>A loan to be paid in cash says so ({@code payoutMethod} CASH): it has no merchant, voucher validity or
 * unspent-voucher terms (all null), and is paid into {@code payoutAccount}, shown only by its last four digits
 * ({@code ****3123}). A voucher loan carries neither field, so its quote reads exactly as it did before cash payouts
 * existed.
 */
public record StaffLoanQuote(Long offerId, BigDecimal amount, String currency, BigDecimal interestRate,
                             BigDecimal interest, BigDecimal fees, BigDecimal totalRepayable, LocalDate repaymentDate,
                             String collection, String merchantName, String redemption, Integer voucherValidityDays,
                             String unredeemedVoucher, StaffLoanAgreementText agreement,
                             @JsonInclude(JsonInclude.Include.NON_NULL) StaffLoanPayoutMethod payoutMethod,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String payoutAccount) {
}
