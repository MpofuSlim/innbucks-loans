package zw.co.innbucks.loans.core.getmore.simulator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A simulated Magento cart price rule ({@code salesrule}). The id is assigned, not generated: the simulator answers
 * for the rule loans is configured with ({@code GETMORE_RULE_ID}, 43 by default), seeded at boot by
 * {@link SimulatedStoreSeeder}.
 */
@Entity
@Table(name = "getmore_sim_rules")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SimRule {

    @Id
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    /** {@code by_percent}, {@code by_fixed}, {@code cart_fixed} or {@code buy_x_get_y}. */
    @Column(name = "simple_action", length = 16, nullable = false)
    private String simpleAction;

    @Column(name = "discount_amount", precision = 12, scale = 4, nullable = false)
    private BigDecimal discountAmount;

    /** {@code NO_COUPON}, {@code SPECIFIC_COUPON} or {@code AUTO}. */
    @Column(name = "coupon_type", length = 16, nullable = false)
    private String couponType;

    @Column(name = "use_auto_generation", nullable = false)
    private boolean useAutoGeneration;

    /** 0 is unlimited. */
    @Column(name = "uses_per_coupon", nullable = false)
    private int usesPerCoupon;

    @Column(name = "uses_per_customer", nullable = false)
    private int usesPerCustomer;

    @Column(name = "stop_rules_processing", nullable = false)
    private boolean stopRulesProcessing;

    @Column(name = "apply_to_shipping", nullable = false)
    private boolean applyToShipping;

    @Column(name = "times_used", nullable = false)
    private int timesUsed;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Whether coupons can be generated under it (Magento: auto-generation on, or the AUTO coupon type). */
    public boolean generatesCoupons() {
        return useAutoGeneration || "AUTO".equals(couponType);
    }
}
