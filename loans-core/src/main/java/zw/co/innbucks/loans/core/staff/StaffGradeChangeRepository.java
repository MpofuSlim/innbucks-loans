package zw.co.innbucks.loans.core.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StaffGradeChangeRepository extends JpaRepository<StaffGradeChange, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StaffGradeChange c where c.id = :id")
    Optional<StaffGradeChange> findByIdForUpdate(@Param("id") Long id);

    /** The pending proposal that names the grade, as the grade it changes or as a new name. */
    @Query("select c from StaffGradeChange c where c.status = :status and (c.grade = :grade or c.newGrade = :grade)")
    List<StaffGradeChange> findNaming(@Param("grade") String grade, @Param("status") StaffGradeChangeStatus status);

    List<StaffGradeChange> findAllByOrderByIdDesc();
}
