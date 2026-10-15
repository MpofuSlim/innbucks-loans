package zw.co.innbucks.loans.core.getmore.simulator;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SimOrderRepository extends JpaRepository<SimOrder, Long>, JpaSpecificationExecutor<SimOrder> {

    /**
     * A sale, unless one already holds its reference: a concurrent sale with the same reference waits for the first's
     * commit and then inserts nothing, and the caller re-reads it ({@link #findBySaleReference}). Never a unique
     * violation, so never a 500.
     *
     * @return 1 when inserted, 0 when the reference was taken
     */
    @Modifying
    @Query(value = "INSERT INTO getmore_sim_orders (increment_id, sale_reference, coupon_code, applied_rule_ids,"
            + " store_id, store_name, state, status, order_currency_code, base_currency_code, subtotal, grand_total,"
            + " discount_amount, discount_invoiced, discount_refunded, discount_canceled, created_at, updated_at,"
            + " version)"
            + " VALUES (:incrementId, :saleReference, :couponCode, :appliedRuleIds, :storeId, :storeName, :state,"
            + " :state, :currency, :currency, :subtotal, :grandTotal, :discountAmount, :discountInvoiced, 0, 0, :at,"
            + " :at, 0)"
            + " ON CONFLICT (sale_reference) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("incrementId") String incrementId, @Param("saleReference") String saleReference,
                       @Param("couponCode") String couponCode, @Param("appliedRuleIds") String appliedRuleIds,
                       @Param("storeId") int storeId, @Param("storeName") String storeName,
                       @Param("state") String state, @Param("currency") String currency,
                       @Param("subtotal") BigDecimal subtotal, @Param("grandTotal") BigDecimal grandTotal,
                       @Param("discountAmount") BigDecimal discountAmount,
                       @Param("discountInvoiced") BigDecimal discountInvoiced, @Param("at") LocalDateTime at);

    Optional<SimOrder> findBySaleReference(String saleReference);

    /** The sale with this order number, locked until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SimOrder o where upper(o.incrementId) = upper(:incrementId)")
    Optional<SimOrder> lockByIncrementId(@Param("incrementId") String incrementId);

    /** Every order placed with this code (orders keep the code's text after the coupon is deleted), oldest first. */
    @Query("select o from SimOrder o where upper(o.couponCode) = upper(:code) order by o.entityId")
    List<SimOrder> findByCouponCodeIgnoringCase(@Param("code") String code);

    @Query(value = "SELECT nextval('getmore_sim_order_number_seq')", nativeQuery = true)
    long nextOrderNumber();
}
