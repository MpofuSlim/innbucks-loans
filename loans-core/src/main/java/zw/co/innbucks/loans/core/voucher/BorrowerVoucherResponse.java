package zw.co.innbucks.loans.core.voucher;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A Staff Grocery Loan's voucher as its borrower sees it in the SuperApp (FR-SGL-030, FR-SGL-037). {@code code} (to
 * read out) and {@code scanValue} (for the QR code) are there only while it can still be spent, and never in a log. A
 * GetMore code is given exactly as GetMore made it in both; loans' own is grouped to read and digits only to scan.
 *
 * @param balance         what can still be spent: for a GetMore voucher, less what is ordered at GetMore and not yet
 *                        invoiced, as the last sync saw it
 * @param store           whose code it is: LOANS or GETMORE
 * @param partialSpending whether it can be spent over several purchases
 * @param spent           what has been spent, net of refunds
 * @param simulated       GetMore only, and only on staging's simulated store: true
 * @param balanceAsOf     GetMore only: when the balance was last read from GetMore (when it was issued, if never)
 */
public record BorrowerVoucherResponse(VoucherStatus status, BigDecimal faceValue, BigDecimal balance, String currency,
                                      LocalDateTime expiresAt, String maskedCode, String code, String scanValue,
                                      VoucherCodeSource store, boolean partialSpending, BigDecimal spent,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) Boolean simulated,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime balanceAsOf) {

    @Override
    public String toString() {
        return "BorrowerVoucherResponse[status=" + status + ", maskedCode=" + maskedCode + ", store=" + store + "]";
    }
}
