package zw.co.innbucks.loans.core.getmore.simulator;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SimCreditMemoRepository extends JpaRepository<SimCreditMemo, Long> {

    /**
     * A credit memo, unless one already holds its reference (a concurrent refund with the same reference inserts
     * nothing; the caller re-reads it).
     *
     * @return 1 when inserted, 0 when the reference was taken
     */
    @Modifying
    @Query(value = "INSERT INTO getmore_sim_creditmemos (increment_id, refund_reference, order_id, state,"
            + " discount_amount, subtotal, grand_total, created_at)"
            + " VALUES (:incrementId, :refundReference, :orderId, :state, :discountAmount, :subtotal, :grandTotal, :at)"
            + " ON CONFLICT (refund_reference) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("incrementId") String incrementId, @Param("refundReference") String refundReference,
                       @Param("orderId") long orderId, @Param("state") int state,
                       @Param("discountAmount") BigDecimal discountAmount, @Param("subtotal") BigDecimal subtotal,
                       @Param("grandTotal") BigDecimal grandTotal, @Param("at") LocalDateTime at);

    Optional<SimCreditMemo> findByRefundReference(String refundReference);

    List<SimCreditMemo> findByOrderIdInOrderByEntityIdAsc(Collection<Long> orderIds);

    Page<SimCreditMemo> findByOrderIdIn(Collection<Long> orderIds, Pageable pageable);

    @Query(value = "SELECT nextval('getmore_sim_creditmemo_number_seq')", nativeQuery = true)
    long nextCreditMemoNumber();
}
