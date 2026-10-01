package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StaffLoanRepository extends JpaRepository<StaffLoan, Long>, JpaSpecificationExecutor<StaffLoan> {

    /** The loans that bear on whether these members may borrow: open ones, and written-off ones. */
    List<StaffLoan> findByStaffMemberIdInAndStatusIn(Collection<Long> staffMemberIds,
                                                    Collection<StaffLoanStatus> statuses);

    Optional<StaffLoan> findFirstByStaffMemberIdAndStatusInOrderByIdDesc(Long staffMemberId,
                                                                         Collection<StaffLoanStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StaffLoan l where l.id = :id")
    Optional<StaffLoan> lockById(@Param("id") Long id);

    @Query(value = "SELECT nextval('staff_loan_reference_seq')", nativeQuery = true)
    long nextReferenceNumber();
}
