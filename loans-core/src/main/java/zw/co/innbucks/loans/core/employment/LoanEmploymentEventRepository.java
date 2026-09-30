package zw.co.innbucks.loans.core.employment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LoanEmploymentEventRepository extends JpaRepository<LoanEmploymentEvent, Long> {

    /** The officers' queue: open holds and reviews, oldest first. */
    List<LoanEmploymentEvent> findByStatusOrderByIdAsc(LoanEmploymentEventStatus status);

    List<LoanEmploymentEvent> findByEventIdInOrderByIdAsc(List<Long> eventIds);

    List<LoanEmploymentEvent> findByLoanIdOrderByIdAsc(Long loanId);

    /** Locked, so two officers cannot resolve it at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from LoanEmploymentEvent e where e.id = :id")
    Optional<LoanEmploymentEvent> findByIdForUpdate(@Param("id") Long id);
}
