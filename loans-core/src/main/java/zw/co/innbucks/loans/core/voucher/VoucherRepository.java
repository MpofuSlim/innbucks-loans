package zw.co.innbucks.loans.core.voucher;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VoucherRepository extends JpaRepository<Voucher, Long>, JpaSpecificationExecutor<Voucher> {

    Optional<Voucher> findByDisbursementReference(String disbursementReference);

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
