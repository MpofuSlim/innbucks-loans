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
 * A simulated Magento coupon ({@code salesrule_coupon}), plus the simulator's own {@link #allowance} (assumption A1,
 * not a Magento column). A deleted coupon keeps its row ({@link #deletedAt}) and is invisible to every store call;
 * orders keep their {@code coupon_code} text. The code never appears in {@link #toString()}.
 */
@Entity
@Table(name = "getmore_sim_coupons")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "coupon_id")
    private Long couponId;

    @Column(name = "rule_id", nullable = false)
    private long ruleId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "usage_per_customer")
    private Integer usagePerCustomer;

    /** Orders placed with it; never an amount, and never down on a refund. */
    @Column(name = "times_used", nullable = false)
    private int timesUsed;

    /** Stored by a PUT; Magento never enforces it, the simulator's till does (assumption A4). */
    @Column(name = "expiration_date")
    private LocalDateTime expirationDate;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    /** 0 manual, 1 generated. */
    @Column(name = "type", nullable = false)
    private int type;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Assumption A1: what this coupon is worth, set once by the amount binding. */
    @Column(name = "allowance", precision = 12, scale = 4)
    private BigDecimal allowance;

    @Column(name = "allowance_currency", length = 3)
    private String allowanceCurrency;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Never the code: {@code ****8B1C}. */
    @Override
    public String toString() {
        return "SimCoupon[couponId=" + couponId + ", ruleId=" + ruleId + ", code=" + GetMoreCodes.maskedCode(code)
                + ", timesUsed=" + timesUsed + ", allowance=" + allowance + " " + allowanceCurrency + ", deletedAt="
                + deletedAt + "]";
    }
}
