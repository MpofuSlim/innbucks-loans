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
import java.util.Optional;

public interface VoucherRedemptionRepository extends JpaRepository<VoucherRedemption, Long> {

    Optional<VoucherRedemption> findByMerchantIdAndMerchantReference(Long merchantId, String merchantReference);

    List<VoucherRedemption> findByVoucherIdOrderByIdAsc(Long voucherId);

    boolean existsByVoucherId(Long voucherId);

    List<VoucherRedemption> findByVoucherIdInOrderByIdAsc(Collection<Long> voucherIds);

    /** A till's redemptions in the window, by when they were made. */
    List<VoucherRedemption> findBySourceAndRedeemedAtBetweenOrderByIdAsc(VoucherRedemptionSource source,
                                                                        LocalDateTime from, LocalDateTime to);

    /** GetMore's sales read back in the window, by when loans recorded them, whenever GetMore made them. */
    List<VoucherRedemption> findBySourceAndRecordedAtBetweenOrderByIdAsc(VoucherRedemptionSource source,
                                                                        LocalDateTime from, LocalDateTime to);

    /**
     * GetMore's sales in one store not yet paid to GetMore by a finalised payday, made before {@code cutoff} (GetMore's
     * own time): what a payday's settlement takes, oldest row first.
     */
    default List<VoucherRedemption> findUnsettledBefore(GetMoreMode storeMode, LocalDateTime cutoff) {
        return unsettledBefore(VoucherRedemptionSource.GETMORE_SYNC, storeMode, cutoff);
    }

    @Query("select r from VoucherRedemption r where r.source = :source and r.storeMode = :mode"
            + " and r.settlementCycle is null and r.redeemedAt < :cutoff order by r.id")
    List<VoucherRedemption> unsettledBefore(@Param("source") VoucherRedemptionSource source,
                                            @Param("mode") GetMoreMode storeMode,
                                            @Param("cutoff") LocalDateTime cutoff);

    /** GetMore's sales in one store that the payday's finalised cycle paid GetMore for. */
    List<VoucherRedemption> findBySettlementCycleAndStoreModeOrderByIdAsc(LocalDate settlementCycle,
                                                                          GetMoreMode storeMode);

    /**
     * Stamps these sales as paid to GetMore on {@code payday}, each only if no payday has taken it yet; how many. The
     * one writer of {@code settlement_cycle}: a native update, since the entity never writes the column.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE voucher_redemptions SET settlement_cycle = :payday WHERE id IN (:ids)"
            + " AND settlement_cycle IS NULL", nativeQuery = true)
    int stampSettlementCycle(@Param("ids") Collection<Long> ids, @Param("payday") LocalDate payday);
}
