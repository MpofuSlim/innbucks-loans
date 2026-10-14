package zw.co.innbucks.loans.core.getmore.simulator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A simulated Magento order: one sale at the test till. Signs are Magento's: {@code discountAmount},
 * {@code discountInvoiced} and {@code discountRefunded} are 0 or negative, {@code discountCanceled} 0 or positive; the
 * columns are never null (0 where Magento would leave the key out). Inserted by
 * {@link SimOrderRepository#insertIfAbsent}, idempotent on {@link #saleReference}. The code never appears in
 * {@link #toString()}.
 */
@Entity
@Table(name = "getmore_sim_orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimOrder {

    public static final String PROCESSING = "processing";
    public static final String COMPLETE = "complete";
    public static final String CLOSED = "closed";
    public static final String CANCELED = "canceled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entity_id")
    private Long entityId;

    /** {@code SIM} and 9 digits. */
    @Column(name = "increment_id", length = 32, nullable = false)
    private String incrementId;

    /** The test till's reference: a sale is idempotent on it. */
    @Column(name = "sale_reference", length = 64, nullable = false)
    private String saleReference;

    @Column(name = "coupon_code")
    private String couponCode;

    /** Comma-separated, as Magento stores it ({@code "43"}). */
    @Column(name = "applied_rule_ids")
    private String appliedRuleIds;

    @Column(name = "store_id", nullable = false)
    private Integer storeId;

    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Column(name = "state", length = 32, nullable = false)
    private String state;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "order_currency_code", length = 3, nullable = false)
    private String orderCurrencyCode;

    @Column(name = "base_currency_code", length = 3, nullable = false)
    private String baseCurrencyCode;

    @Column(name = "subtotal", precision = 12, scale = 4, nullable = false)
    private BigDecimal subtotal;

    @Column(name = "grand_total", precision = 12, scale = 4, nullable = false)
    private BigDecimal grandTotal;

    @Column(name = "discount_amount", precision = 12, scale = 4, nullable = false)
    private BigDecimal discountAmount;

    @Column(name = "discount_invoiced", precision = 12, scale = 4, nullable = false)
    private BigDecimal discountInvoiced;

    @Column(name = "discount_refunded", precision = 12, scale = 4, nullable = false)
    private BigDecimal discountRefunded;

    @Column(name = "discount_canceled", precision = 12, scale = 4, nullable = false)
    private BigDecimal discountCanceled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** What the coupon took off this order: {@code |discount_amount|}. */
    public BigDecimal spent() {
        return discountAmount.abs();
    }

    /** Invoiced and not yet refunded: {@code |discount_invoiced| − |discount_refunded|}. */
    public BigDecimal refundable() {
        return discountInvoiced.abs().subtract(discountRefunded.abs());
    }

    /** Never the coupon code: {@code ****8B1C}. */
    @Override
    public String toString() {
        return "SimOrder[entityId=" + entityId + ", incrementId=" + incrementId + ", couponCode="
                + GetMoreCodes.maskedCode(couponCode) + ", state=" + state + ", discountAmount=" + discountAmount
                + ", discountInvoiced=" + discountInvoiced + ", discountRefunded=" + discountRefunded + "]";
    }
}
