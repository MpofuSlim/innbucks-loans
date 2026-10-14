package zw.co.innbucks.loans.core.voucher;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One market day of vouchers, for settlement and reconciliation with each merchant (FR-SGL-038, FR-GEN-009): what was
 * issued, what was redeemed at each of its outlets, what was refunded, and what was cancelled or lapsed with something
 * left. {@code totals} are per currency, over every merchant in the report; {@code byMerchant} splits them per merchant
 * and currency, which is what each merchant is settled on. {@code lines} is every one of those events, in time order,
 * with the code masked.
 *
 * <p>A till's redemption is on the day it was made. A GetMore sale or refund is on the day loans read it back from
 * GetMore ({@code at}), with GetMore's own time beside it ({@code occurredAt}), so a past day's report never changes
 * because a sync ran late.</p>
 *
 * @param merchantCode the one merchant the report was asked for; absent when it covers them all
 * @param simulated    true only on a host whose GetMore store is SIMULATED (staging), whose GetMore rows are a test's;
 *                     absent everywhere else, where those rows are left out
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VoucherSettlementReport(LocalDate date, String merchantCode, LocalDateTime generatedAt,
                                      List<CurrencyTotals> totals, List<MerchantTotals> byMerchant,
                                      List<OutletTotals> redemptionsByOutlet, List<Line> lines, Boolean simulated) {

    /**
     * What happened to a voucher that day. ISSUED is the credit extended as the voucher; nothing is paid to the
     * merchant until payday. REFUNDED is a GetMore sale given back by a credit memo.
     */
    public enum Event { ISSUED, REDEEMED, REFUNDED, CANCELLED, EXPIRED }

    /** {@code redeemedValue} is every purchase; {@code netRedeemedValue} is that less what was refunded. */
    public record CurrencyTotals(String currency, long issuedCount, BigDecimal issuedValue, long redemptionCount,
                                 BigDecimal redeemedValue, long refundCount, BigDecimal refundedValue,
                                 BigDecimal netRedeemedValue, long cancelledCount, BigDecimal cancelledValue,
                                 long expiredCount, BigDecimal expiredUnredeemedValue) {
    }

    public record MerchantTotals(String merchantCode, String merchantName, String currency, long issuedCount,
                                 BigDecimal issuedValue, long redemptionCount, BigDecimal redeemedValue,
                                 long refundCount, BigDecimal refundedValue, BigDecimal netRedeemedValue,
                                 long cancelledCount, BigDecimal cancelledValue, long expiredCount,
                                 BigDecimal expiredUnredeemedValue) {
    }

    /**
     * An outlet is the merchant's own, so two merchants' outlets with the same id are kept apart. A refund is the
     * outlet's whose sale it gives back.
     */
    public record OutletTotals(String merchantCode, String outletId, String outletName, String currency,
                               long redemptionCount, BigDecimal redeemedValue, long refundCount,
                               BigDecimal refundedValue, BigDecimal netRedeemedValue) {
    }

    /**
     * One event. {@code amount} is the face value for ISSUED and CANCELLED, the purchase for REDEEMED, what was given
     * back for REFUNDED, and what was left unspent for EXPIRED. Outlet and merchant reference are set on REDEEMED and
     * REFUNDED only. {@code occurredAt} is GetMore's own time for a GetMore sale or refund ({@code at} is when loans
     * read it back), and absent otherwise.
     */
    public record Line(Event event, LocalDateTime at,
                       @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime occurredAt, Long voucherId,
                       String merchantCode, String maskedCode, String loanAccount, String customerReference,
                       String currency, BigDecimal amount, String outletId, String outletName,
                       String merchantReference) {
    }
}
