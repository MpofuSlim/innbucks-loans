package zw.co.innbucks.loans.core.voucher;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One market day of vouchers, for settlement and reconciliation with each merchant (FR-SGL-038, FR-GEN-009): what was
 * issued (the value paid to the merchant's settlement account at disbursement), what was redeemed at each of its
 * outlets, and what was cancelled or lapsed with something left. {@code totals} are per currency, over every merchant
 * in the report; {@code byMerchant} splits them per merchant and currency, which is what each merchant is settled on.
 * {@code lines} is every one of those events, in time order, with the code masked.
 *
 * @param merchantCode the one merchant the report was asked for; absent when it covers them all
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VoucherSettlementReport(LocalDate date, String merchantCode, LocalDateTime generatedAt,
                                      List<CurrencyTotals> totals, List<MerchantTotals> byMerchant,
                                      List<OutletTotals> redemptionsByOutlet, List<Line> lines) {

    /** What happened to a voucher that day. */
    public enum Event { ISSUED, REDEEMED, CANCELLED, EXPIRED }

    public record CurrencyTotals(String currency, long issuedCount, BigDecimal issuedValue, long redemptionCount,
                                 BigDecimal redeemedValue, long cancelledCount, BigDecimal cancelledValue,
                                 long expiredCount, BigDecimal expiredUnredeemedValue) {
    }

    public record MerchantTotals(String merchantCode, String merchantName, String currency, long issuedCount,
                                 BigDecimal issuedValue, long redemptionCount, BigDecimal redeemedValue,
                                 long cancelledCount, BigDecimal cancelledValue, long expiredCount,
                                 BigDecimal expiredUnredeemedValue) {
    }

    /** An outlet is the merchant's own, so two merchants' outlets with the same id are kept apart. */
    public record OutletTotals(String merchantCode, String outletId, String outletName, String currency,
                               long redemptionCount, BigDecimal redeemedValue) {
    }

    /**
     * One event. {@code amount} is the face value for ISSUED and CANCELLED, the purchase for REDEEMED, and what was left
     * unspent for EXPIRED. Outlet and merchant reference are set on REDEEMED only.
     */
    public record Line(Event event, LocalDateTime at, Long voucherId, String merchantCode, String maskedCode,
                       String loanAccount, String customerReference, String currency, BigDecimal amount,
                       String outletId, String outletName, String merchantReference) {
    }
}
