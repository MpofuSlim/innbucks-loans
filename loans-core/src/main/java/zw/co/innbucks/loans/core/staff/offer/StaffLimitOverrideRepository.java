package zw.co.innbucks.loans.core.staff.offer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StaffLimitOverrideRepository extends JpaRepository<StaffLimitOverride, Long>,
        JpaSpecificationExecutor<StaffLimitOverride> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StaffLimitOverride o where o.id = :id")
    Optional<StaffLimitOverride> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StaffLimitOverride o where o.staffMemberId = :staffMemberId and o.status = :status")
    Optional<StaffLimitOverride> findForUpdate(@Param("staffMemberId") Long staffMemberId,
                                               @Param("status") StaffLimitOverrideStatus status);

    Optional<StaffLimitOverride> findByStaffMemberIdAndStatus(Long staffMemberId, StaffLimitOverrideStatus status);

    List<StaffLimitOverride> findByStatus(StaffLimitOverrideStatus status);
}
