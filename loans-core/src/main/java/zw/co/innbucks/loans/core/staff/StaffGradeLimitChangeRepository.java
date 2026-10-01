package zw.co.innbucks.loans.core.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StaffGradeLimitChangeRepository extends JpaRepository<StaffGradeLimitChange, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StaffGradeLimitChange c where c.id = :id")
    Optional<StaffGradeLimitChange> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StaffGradeLimitChange c where c.grade = :grade and c.effectiveFrom = :effectiveFrom"
            + " and c.status = :status")
    Optional<StaffGradeLimitChange> findForUpdate(@Param("grade") String grade,
                                                  @Param("effectiveFrom") LocalDate effectiveFrom,
                                                  @Param("status") StaffGradeLimitChangeStatus status);

    Optional<StaffGradeLimitChange> findByGradeAndEffectiveFromAndStatus(String grade, LocalDate effectiveFrom,
                                                                         StaffGradeLimitChangeStatus status);

    List<StaffGradeLimitChange> findByStatusIn(Collection<StaffGradeLimitChangeStatus> statuses);

    boolean existsByGradeAndStatus(String grade, StaffGradeLimitChangeStatus status);

    List<StaffGradeLimitChange> findByGradeAndStatusOrderByEffectiveFrom(String grade,
                                                                          StaffGradeLimitChangeStatus status);

    @Query("select distinct c.grade from StaffGradeLimitChange c where c.status = :status")
    List<String> findGradesByStatus(@Param("status") StaffGradeLimitChangeStatus status);

    /** The approved limit in force for the grade on the day: the latest effective on or before it. */
    Optional<StaffGradeLimitChange> findFirstByGradeAndStatusAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            String grade, StaffGradeLimitChangeStatus status, LocalDate on);
}
