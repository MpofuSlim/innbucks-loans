package zw.co.innbucks.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditDecisionRepository extends JpaRepository<CreditDecision, Long> {

    /** A loan's credit history, oldest first. */
    List<CreditDecision> findByLoanIdOrderByIdAsc(Long loanId);

    boolean existsByLoanIdAndActionAndPerformedByIgnoreCase(Long loanId, CreditAction action, String performedBy);
}
