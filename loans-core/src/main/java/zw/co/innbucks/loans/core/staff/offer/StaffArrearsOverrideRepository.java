package zw.co.innbucks.loans.core.staff.offer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StaffArrearsOverrideRepository extends JpaRepository<StaffArrearsOverride, Long>,
        JpaSpecificationExecutor<StaffArrearsOverride> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StaffArrearsOverride o where o.id = :id")
    Optional<StaffArrearsOverride> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from StaffArrearsOverride o where o.staffMemberId = :staffMemberId and o.status = :status")
    Optional<StaffArrearsOverride> findForUpdate(@Param("staffMemberId") Long staffMemberId,
                                                 @Param("status") StaffArrearsOverrideStatus status);

    Optional<StaffArrearsOverride> findByStaffMemberIdAndStatus(Long staffMemberId,
                                                                StaffArrearsOverrideStatus status);

    List<StaffArrearsOverride> findByStatus(StaffArrearsOverrideStatus status);
}
