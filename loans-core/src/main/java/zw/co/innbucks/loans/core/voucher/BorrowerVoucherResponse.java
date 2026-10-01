package zw.co.innbucks.loans.core.voucher;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A Staff Grocery Loan's voucher as its borrower sees it in the SuperApp (FR-SGL-030, FR-SGL-037). {@code code} (to
 * read out) and {@code scanValue} (digits only, for the QR code) are there only while it can still be spent, and
 * never in a log.
 */
public record BorrowerVoucherResponse(VoucherStatus status, BigDecimal faceValue, BigDecimal balance, String currency,
                                      LocalDateTime expiresAt, String maskedCode, String code, String scanValue) {

    @Override
    public String toString() {
        return "BorrowerVoucherResponse[status=" + status + ", maskedCode=" + maskedCode + "]";
    }
}
