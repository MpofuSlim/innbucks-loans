package zw.co.innbucks.loans.core.voucher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One purchase paid with a voucher at a till of its merchant (FR-SGL-035, 036). The merchant's own transaction
 * reference identifies it, so a till that sends the same redemption again gets the first answer back instead of
 * spending twice.
 *
 * <p>A GetMore voucher's spends are read back from GetMore's orders instead
 * ({@link VoucherRedemptionSource#GETMORE_SYNC}): one row per order per invoiced level, under
 * {@code GM:O:<order>:<level in cents>} ({@code GMS:} when simulated), so a level is recorded once however often it is
 * read. Its {@code redeemedAt} is GetMore's own time for the sale and {@code recordedAt} when loans read it.</p>
 */
@Entity
@Table(name = "voucher_redemptions")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class VoucherRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;

    /** The merchant whose till took it: the voucher's own, the only one that may. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** The merchant's own reference for the sale: unique among that merchant's redemptions. */
    @Column(name = "merchant_reference", length = 64, nullable = false)
    private String merchantReference;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    /** What was left on the voucher after this purchase. */
    @Column(name = "balance_after", precision = 19, scale = 2, nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "outlet_id", length = 64, nullable = false)
    private String outletId;

    @Column(name = "outlet_name", length = 120)
    private String outletName;

    /** The till account that redeemed it. */
    @Column(name = "redeemed_by", nullable = false)
    private String redeemedBy;

    @Column(name = "redeemed_at", nullable = false)
    private LocalDateTime redeemedAt;

    /** How it reached loans: a till, or a GetMore sync. */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    @Builder.Default
    private VoucherRedemptionSource source = VoucherRedemptionSource.TILL;

    /** GETMORE_SYNC only: the store the order was read from. */
    @Enumerated(EnumType.STRING)
    @Column(name = "store_mode", length = 16)
    private GetMoreMode storeMode;

    /** GETMORE_SYNC only: Magento's order {@code entity_id}. */
    @Column(name = "external_order_id")
    private Long externalOrderId;

    /** GETMORE_SYNC only: GetMore's own order number ({@code increment_id}), e.g. {@code SIM000000012}. */
    @Column(name = "external_order_number", length = 50)
    private String externalOrderNumber;

    /** GETMORE_SYNC only: when loans read it back. */
    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;
}
