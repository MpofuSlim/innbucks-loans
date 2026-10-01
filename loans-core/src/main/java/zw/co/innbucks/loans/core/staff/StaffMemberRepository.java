package zw.co.innbucks.loans.core.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StaffMemberRepository extends JpaRepository<StaffMember, Long>, JpaSpecificationExecutor<StaffMember> {

    Optional<StaffMember> findByEmployeeNumber(String employeeNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from StaffMember m where m.employeeNumber = :employeeNumber")
    Optional<StaffMember> findByEmployeeNumberForUpdate(@Param("employeeNumber") String employeeNumber);

    Optional<StaffMember> findByMsisdn(String msisdn);

    List<StaffMember> findByEmployeeNumberIn(Collection<String> employeeNumbers);

    List<StaffMember> findByMsisdnIn(Collection<String> msisdns);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from StaffMember m where m.grade = :grade order by m.id")
    List<StaffMember> findByGradeForUpdate(@Param("grade") String grade);

    long countByGrade(String grade);

    long countByGradeAndEmploymentStatusIn(String grade, Collection<StaffEmploymentStatus> statuses);

    /**
     * Serialises every write to the register: approvals take this transaction-scoped lock first, so one approval
     * re-validates its rows against everything the previous one applied (a mobile number it gave someone, say).
     */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(:key)) as locked", nativeQuery = true)
    Integer lockRegister(@Param("key") long key);
}
