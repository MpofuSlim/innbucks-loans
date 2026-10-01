package zw.co.innbucks.loans.core.voucher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One market day of vouchers, for settlement and reconciliation with GetMore (FR-SGL-038, FR-GEN-009): what was issued
 * (the value paid to GetMore's settlement account at disbursement), what was redeemed at each outlet, and what was
 * cancelled or lapsed with something left. Totals are per currency; {@code lines} is every one of those events, in
 * time order, with the code masked.
 */
public record VoucherSettlementReport(LocalDate date, LocalDateTime generatedAt, List<CurrencyTotals> totals,
                                      List<OutletTotals> redemptionsByOutlet, List<Line> lines) {

    /** What happened to a voucher that day. */
    public enum Event { ISSUED, REDEEMED, CANCELLED, EXPIRED }

    public record CurrencyTotals(String currency, long issuedCount, BigDecimal issuedValue, long redemptionCount,
                                 BigDecimal redeemedValue, long cancelledCount, BigDecimal cancelledValue,
                                 long expiredCount, BigDecimal expiredUnredeemedValue) {
    }

    public record OutletTotals(String outletId, String outletName, String currency, long redemptionCount,
                               BigDecimal redeemedValue) {
    }

    /**
     * One event. {@code amount} is the face value for ISSUED and CANCELLED, the purchase for REDEEMED, and what was left
     * unspent for EXPIRED. Outlet and merchant reference are set on REDEEMED only.
     */
    public record Line(Event event, LocalDateTime at, Long voucherId, String maskedCode, String loanAccount,
                       String customerReference, String currency, BigDecimal amount, String outletId,
                       String outletName, String merchantReference) {
    }
}
