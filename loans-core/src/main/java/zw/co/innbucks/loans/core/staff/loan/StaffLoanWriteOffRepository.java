package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StaffLoanWriteOffRepository extends JpaRepository<StaffLoanWriteOff, Long>,
        JpaSpecificationExecutor<StaffLoanWriteOff> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from StaffLoanWriteOff w where w.id = :id")
    Optional<StaffLoanWriteOff> findByIdForUpdate(@Param("id") Long id);

    Optional<StaffLoanWriteOff> findByStaffLoanIdAndStatus(Long staffLoanId, StaffLoanWriteOffStatus status);
}
