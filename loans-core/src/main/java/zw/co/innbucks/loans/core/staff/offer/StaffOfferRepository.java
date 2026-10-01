package zw.co.innbucks.loans.core.staff.offer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface StaffOfferRepository extends JpaRepository<StaffOffer, Long>, JpaSpecificationExecutor<StaffOffer> {

    List<StaffOffer> findByStatus(StaffOfferStatus status);

    Optional<StaffOffer> findByStaffMemberIdAndStatus(Long staffMemberId, StaffOfferStatus status);

    /** The members already holding an offer from this cycle, whatever has happened to it since. */
    @Query("select o.staffMemberId from StaffOffer o where o.cycleStart = :cycleStart")
    Set<Long> findMemberIdsByCycleStart(@Param("cycleStart") LocalDate cycleStart);

    /** Lapses every open offer past its expiry, closing it at the moment it expired. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update StaffOffer o set o.status = :expired, o.closedAt = o.expiresAt, o.version = o.version + 1"
            + " where o.status = :active and o.expiresAt <= :now")
    int expireDue(@Param("now") LocalDateTime now, @Param("active") StaffOfferStatus active,
                  @Param("expired") StaffOfferStatus expired);
}
