package zw.co.innbucks.loans.core.voucher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One purchase paid with a voucher at a GetMore till (FR-SGL-035, 036). GetMore's own transaction reference identifies
 * it, so a till that sends the same redemption again gets the first answer back instead of spending twice.
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

    @Column(name = "merchant_reference", length = 64, nullable = false, unique = true)
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

    /** The GetMore account that redeemed it. */
    @Column(name = "redeemed_by", nullable = false)
    private String redeemedBy;

    @Column(name = "redeemed_at", nullable = false)
    private LocalDateTime redeemedAt;
}
