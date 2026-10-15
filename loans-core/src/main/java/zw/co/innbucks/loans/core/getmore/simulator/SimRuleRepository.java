package zw.co.innbucks.loans.core.getmore.simulator;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface SimRuleRepository extends JpaRepository<SimRule, Long> {

    /**
     * The simulator's rule, inserted unless it is already there: active, {@code cart_fixed} worth 0 (each coupon
     * carries its own allowance, assumption A1), AUTO coupons, unlimited uses (spending in parts needs them), no
     * dates.
     *
     * @return 1 when inserted, 0 when it was already there
     */
    @Modifying
    @Query(value = "INSERT INTO getmore_sim_rules (rule_id, name, is_active, simple_action, discount_amount,"
            + " coupon_type, use_auto_generation, uses_per_coupon, uses_per_customer, stop_rules_processing,"
            + " apply_to_shipping, times_used, created_at)"
            + " VALUES (:ruleId, :name, TRUE, 'cart_fixed', 0, 'AUTO', TRUE, 0, 0, TRUE, FALSE, 0, :at)"
            + " ON CONFLICT (rule_id) DO NOTHING", nativeQuery = true)
    int seedIfAbsent(@Param("ruleId") long ruleId, @Param("name") String name, @Param("at") LocalDateTime at);

    /** One more order placed under the rule, as one statement (concurrent sales never lose a count). */
    @Modifying
    @Query("update SimRule r set r.timesUsed = r.timesUsed + 1 where r.ruleId = :ruleId")
    int countUse(@Param("ruleId") long ruleId);
}
