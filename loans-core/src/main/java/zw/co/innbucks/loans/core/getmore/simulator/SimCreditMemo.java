package zw.co.innbucks.loans.core.getmore.simulator;

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
 * A simulated Magento credit memo: a refund against one order. {@code discountAmount} is negative; {@code state} 1
 * open, 2 refunded (the only one the test till writes), 3 cancelled. Inserted by
 * {@link SimCreditMemoRepository#insertIfAbsent}, idempotent on {@link #refundReference}.
 */
@Entity
@Table(name = "getmore_sim_creditmemos")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SimCreditMemo {

    public static final int OPEN = 1;
    public static final int REFUNDED = 2;
    public static final int CANCELED = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entity_id")
    private Long entityId;

    /** {@code SIMCM} and 8 digits. */
    @Column(name = "increment_id", length = 32, nullable = false)
    private String incrementId;

    @Column(name = "refund_reference", length = 64, nullable = false)
    private String refundReference;

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "state", nullable = false)
    private int state;

    @Column(name = "discount_amount", precision = 12, scale = 4, nullable = false)
    private BigDecimal discountAmount;

    @Column(name = "subtotal", precision = 12, scale = 4, nullable = false)
    private BigDecimal subtotal;

    @Column(name = "grand_total", precision = 12, scale = 4, nullable = false)
    private BigDecimal grandTotal;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
