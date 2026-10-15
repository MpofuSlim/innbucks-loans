package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StaffLoanRepository extends JpaRepository<StaffLoan, Long>, JpaSpecificationExecutor<StaffLoan> {

    /** The loans that bear on whether these members may borrow: open ones, and written-off ones. */
    List<StaffLoan> findByStaffMemberIdInAndStatusIn(Collection<Long> staffMemberIds,
                                                    Collection<StaffLoanStatus> statuses);

    /** Whether the member has a loan in {@code status}: a written-off one, say. */
    boolean existsByStaffMemberIdAndStatus(Long staffMemberId, StaffLoanStatus status);

    /**
     * Paid-out loans not recovered in full on {@code today} (FR-SGL-045): written off; or still DISBURSED and past their
     * due date, or with a borrower no longer ACTIVE. With their merchant.
     */
    @Query("select l from StaffLoan l join fetch l.merchant where l.status = :writtenOff or (l.status = :disbursed"
            + " and (l.dueDate < :today or l.employmentFlag is not null))")
    List<StaffLoan> findNotRecovered(@Param("today") LocalDate today,
                                     @Param("disbursed") StaffLoanStatus disbursed,
                                     @Param("writtenOff") StaffLoanStatus writtenOff);

    /** The loans due on a payday in {@code statuses}, with their merchant: who a collection statement is about. */
    @Query("select l from StaffLoan l join fetch l.merchant where l.dueDate = :payday and l.status in :statuses"
            + " order by l.id")
    List<StaffLoan> findDueOn(@Param("payday") LocalDate payday,
                              @Param("statuses") Collection<StaffLoanStatus> statuses);

    /** A loan by its reference ({@code SGL-2026-000164}). */
    Optional<StaffLoan> findByReference(String reference);

    /** A loan by its reference, only if it is the member's. */
    Optional<StaffLoan> findByStaffMemberIdAndReference(Long staffMemberId, String reference);

    /** One member's loans, whatever became of them. */
    Page<StaffLoan> findByStaffMemberId(Long staffMemberId, Pageable pageable);

    Optional<StaffLoan> findFirstByStaffMemberIdAndStatusInOrderByIdDesc(Long staffMemberId,
                                                                         Collection<StaffLoanStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from StaffLoan l where l.id = :id")
    Optional<StaffLoan> lockById(@Param("id") Long id);

    @Query(value = "SELECT nextval('staff_loan_reference_seq')", nativeQuery = true)
    long nextReferenceNumber();
}
