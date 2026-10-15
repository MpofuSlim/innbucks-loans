package zw.co.innbucks.loans.core.voucher;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VoucherRepository extends JpaRepository<Voucher, Long>, JpaSpecificationExecutor<Voucher> {

    Optional<Voucher> findByDisbursementReference(String disbursementReference);

    Optional<Voucher> findFirstByStaffMemberIdAndLoanAccountOrderByIdDesc(Long staffMemberId, String loanAccount);

    /** A Staff Grocery Loan's GetMore voucher: one per loan. */
    Optional<Voucher> findByStaffLoanId(Long staffLoanId);

    List<Voucher> findByStaffLoanIdIn(Collection<Long> staffLoanIds);

    boolean existsByStoreMode(GetMoreMode storeMode);

    long countByStoreMode(GetMoreMode storeMode);

    /** The voucher a GetMore coupon was kept as, in its store: one per coupon ({@code uq_vouchers_external_coupon}). */
    Optional<Voucher> findByStoreModeAndExternalCouponId(GetMoreMode storeMode, Long externalCouponId);

    /** GetMore vouchers of one store whose last sync stopped at a blocking issue. */
    long countByStoreModeAndSyncBlockedByIsNotNull(GetMoreMode storeMode);

    /** GetMore vouchers of one store with a sale ordered there and not yet invoiced, as last synced. */
    long countByStoreModeAndHeldAmountGreaterThan(GetMoreMode storeMode, BigDecimal amount);

    /**
     * A staff member's GetMore vouchers in {@code mode} that a borrower's read may refresh: not cancelled, expiring
     * after {@code expiredAfter} (now less the sync's after-expiry window: a sale can still be invoiced or refunded
     * that long after), and not synced since {@code syncedBefore}. Newest first, at most {@code limit}.
     */
    default List<Long> findGetMoreIdsToRefresh(Long staffMemberId, GetMoreMode mode, LocalDateTime expiredAfter,
                                               LocalDateTime syncedBefore, int limit) {
        return getMoreIdsToRefresh(staffMemberId, mode, expiredAfter, syncedBefore, VoucherCodeSource.GETMORE,
                VoucherStatus.CANCELLED, PageRequest.of(0, limit));
    }

    @Query("select v.id from Voucher v where v.staffMemberId = :member and v.codeSource = :getMore"
            + " and v.storeMode = :mode and v.status <> :cancelled and v.expiresAt > :expiredAfter"
            + " and (v.syncedAt is null or v.syncedAt < :syncedBefore) order by v.id desc")
    List<Long> getMoreIdsToRefresh(@Param("member") Long staffMemberId, @Param("mode") GetMoreMode mode,
                                   @Param("expiredAfter") LocalDateTime expiredAfter,
                                   @Param("syncedBefore") LocalDateTime syncedBefore,
                                   @Param("getMore") VoucherCodeSource getMore,
                                   @Param("cancelled") VoucherStatus cancelled, Pageable pageable);

    /**
     * The GetMore vouchers of one store whose spends or refunds can fall into a payday's cycle, a page at a time by id
     * (keyset: ids above {@code after}, at most {@code limit}): issued before its cut-off ({@code issuedBefore}) and
     * expiring no earlier than {@code expiresFrom} (the previous payday's cut-off less the sync's after-expiry window,
     * since a sale can still be invoiced or refunded that long after a voucher lapses). Every status: a sale that
     * reached a cancelled one is still GetMore's to bill.
     */
    default List<Long> findGetMoreIdsForCycle(GetMoreMode mode, LocalDateTime issuedBefore,
                                              LocalDateTime expiresFrom, long after, int limit) {
        return getMoreIdsForCycle(mode, issuedBefore, expiresFrom, after, VoucherCodeSource.GETMORE,
                PageRequest.of(0, limit));
    }

    @Query("select v.id from Voucher v where v.codeSource = :getMore and v.storeMode = :mode"
            + " and v.issuedAt < :issuedBefore and v.expiresAt >= :expiresFrom and v.id > :after order by v.id")
    List<Long> getMoreIdsForCycle(@Param("mode") GetMoreMode mode, @Param("issuedBefore") LocalDateTime issuedBefore,
                                  @Param("expiresFrom") LocalDateTime expiresFrom, @Param("after") long after,
                                  @Param("getMore") VoucherCodeSource getMore, Pageable pageable);

    /**
     * How many of the vouchers {@link #findGetMoreIdsForCycle} walks were last read from GetMore before
     * {@code syncedBefore} (the payday's cut-off), or never: what the payday sync has still to reach.
     */
    default long countGetMoreStaleForCycle(GetMoreMode mode, LocalDateTime issuedBefore, LocalDateTime expiresFrom,
                                           LocalDateTime syncedBefore) {
        return getMoreStaleForCycle(mode, issuedBefore, expiresFrom, syncedBefore, VoucherCodeSource.GETMORE);
    }

    @Query("select count(v) from Voucher v where v.codeSource = :getMore and v.storeMode = :mode"
            + " and v.issuedAt < :issuedBefore and v.expiresAt >= :expiresFrom"
            + " and (v.syncedAt is null or v.syncedAt < :syncedBefore)")
    long getMoreStaleForCycle(@Param("mode") GetMoreMode mode, @Param("issuedBefore") LocalDateTime issuedBefore,
                              @Param("expiresFrom") LocalDateTime expiresFrom,
                              @Param("syncedBefore") LocalDateTime syncedBefore,
                              @Param("getMore") VoucherCodeSource getMore);

    Optional<Voucher> findByCodeHmac(String codeHmac);

    boolean existsByCodeHmac(String codeHmac);

    /** The voucher with this code, locked until the transaction ends, so two tills cannot spend the same balance. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.codeHmac = :codeHmac")
    Optional<Voucher> lockByCodeHmac(@Param("codeHmac") String codeHmac);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v where v.id = :id")
    Optional<Voucher> lockById(@Param("id") Long id);

    /** Moves the delivery from {@code from} to {@code to}; 0 when another sender got there first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Voucher v set v.deliveryStatus = :to, v.deliveryUpdatedAt = :at, v.version = v.version + 1"
            + " where v.id = :id and v.deliveryStatus = :from")
    int claimDelivery(@Param("id") Long id, @Param("from") VoucherDeliveryStatus from,
                      @Param("to") VoucherDeliveryStatus to, @Param("at") LocalDateTime at);

    /** Ids of vouchers whose delivery stands at {@code status}, after {@code after}, oldest first. */
    @Query("select v.id from Voucher v where v.deliveryStatus = :status and v.id > :after order by v.id")
    List<Long> findIdsByDeliveryStatusAfter(@Param("status") VoucherDeliveryStatus status, @Param("after") long after,
                                            Pageable pageable);

    /** Marks every open voucher that has lapsed by {@code now} EXPIRED; how many. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Voucher v set v.status = :expired, v.version = v.version + 1"
            + " where v.status in :open and v.expiresAt <= :now")
    int expireLapsed(@Param("open") Collection<VoucherStatus> open, @Param("expired") VoucherStatus expired,
                     @Param("now") LocalDateTime now);

    List<Voucher> findByIssuedAtBetweenOrderByIdAsc(LocalDateTime from, LocalDateTime to);

    List<Voucher> findByCancelledAtBetweenOrderByIdAsc(LocalDateTime from, LocalDateTime to);

    /** Vouchers lapsing in the window with something left on them, whether or not the job has marked them yet. */
    List<Voucher> findByExpiresAtBetweenAndStatusInOrderByIdAsc(LocalDateTime from, LocalDateTime to,
                                                                Collection<VoucherStatus> statuses);
}
