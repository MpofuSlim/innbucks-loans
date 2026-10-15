package zw.co.innbucks.loans.core.voucher;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface VoucherRefundRepository extends JpaRepository<VoucherRefund, Long> {

    List<VoucherRefund> findByVoucherIdOrderByIdAsc(Long voucherId);

    boolean existsByVoucherId(Long voucherId);

    List<VoucherRefund> findByVoucherIdInOrderByIdAsc(Collection<Long> voucherIds);

    /** Refunds read back in the window: what a day's settlement report lists, whenever GetMore made them. */
    List<VoucherRefund> findByRecordedAtBetweenOrderByIdAsc(LocalDateTime from, LocalDateTime to);

    /**
     * Refunds in one store not yet taken off what GetMore is paid by a finalised payday, made before {@code cutoff}
     * (GetMore's own time): what a payday's settlement takes, oldest row first.
     */
    @Query("select r from VoucherRefund r where r.storeMode = :mode and r.settlementCycle is null"
            + " and r.refundedAt < :cutoff order by r.id")
    List<VoucherRefund> findUnsettledBefore(@Param("mode") GetMoreMode storeMode,
                                            @Param("cutoff") LocalDateTime cutoff);

    /** Refunds in one store that the payday's finalised cycle took off what GetMore was paid. */
    List<VoucherRefund> findBySettlementCycleAndStoreModeOrderByIdAsc(LocalDate settlementCycle,
                                                                      GetMoreMode storeMode);

    /**
     * Stamps these refunds as settled on {@code payday}, each only if no payday has taken it yet; how many. The one
     * writer of {@code settlement_cycle}: a native update, since the entity never writes the column.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE voucher_refunds SET settlement_cycle = :payday WHERE id IN (:ids)"
            + " AND settlement_cycle IS NULL", nativeQuery = true)
    int stampSettlementCycle(@Param("ids") Collection<Long> ids, @Param("payday") LocalDate payday);
}
