package zw.co.innbucks.loans.core.getmore.simulator;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Codes are compared without case, as Magento's collation does; {@code uq_getmore_sim_coupons_code} keeps them unique
 * that way across every row, deleted ones included, so a code is never handed out twice.
 */
public interface SimCouponRepository extends JpaRepository<SimCoupon, Long>, JpaSpecificationExecutor<SimCoupon> {

    /** The live (not deleted) coupon with this code, locked until the transaction ends: one till at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SimCoupon c where upper(c.code) = upper(:code) and c.deletedAt is null")
    Optional<SimCoupon> lockLiveByCode(@Param("code") String code);

    @Query("select c from SimCoupon c where upper(c.code) = upper(:code) and c.deletedAt is null")
    Optional<SimCoupon> findLiveByCode(@Param("code") String code);

    /** The coupon with this code, deleted or not (there is at most one). */
    @Query("select c from SimCoupon c where upper(c.code) = upper(:code)")
    Optional<SimCoupon> findAnyByCode(@Param("code") String code);

    /** Whether any row, deleted or not, other than {@code exceptId}, holds this code. */
    @Query("select case when count(c) > 0 then true else false end from SimCoupon c"
            + " where upper(c.code) = upper(:code) and c.couponId <> :exceptId")
    boolean codeTakenByAnother(@Param("code") String code, @Param("exceptId") long exceptId);

    /** Whether any row, deleted or not, holds this code. */
    @Query("select case when count(c) > 0 then true else false end from SimCoupon c"
            + " where upper(c.code) = upper(:code)")
    boolean codeTaken(@Param("code") String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SimCoupon c where c.couponId = :id")
    Optional<SimCoupon> lockById(@Param("id") long couponId);

    /** The live coupons whose upper-cased code is one of {@code upperCodes}, locked, by id. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SimCoupon c where upper(c.code) in :upperCodes and c.deletedAt is null order by c.couponId")
    List<SimCoupon> lockLiveByUpperCodeIn(@Param("upperCodes") Collection<String> upperCodes);
}
