package zw.co.innbucks.loans.core.staff.loan;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything the borrower must see before accepting (FR-SGL-026), and the agreement to accept (FR-SGL-027). The
 * sentences are the app's to show as they are: {@code collection} how it is repaid, {@code redemption} where the
 * voucher can be spent, and {@code unredeemedVoucher} what happens when it is not spent in full (OQ-09).
 */
public record StaffLoanQuote(Long offerId, BigDecimal amount, String currency, BigDecimal interestRate,
                             BigDecimal interest, BigDecimal fees, BigDecimal totalRepayable, LocalDate repaymentDate,
                             String collection, String merchantName, String redemption, int voucherValidityDays,
                             String unredeemedVoucher, StaffLoanAgreementText agreement) {
}
