package zw.co.innbucks.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CreditDecisionRepository extends JpaRepository<CreditDecision, Long> {

    /** A loan's credit history, oldest first. */
    List<CreditDecision> findByLoanIdOrderByIdAsc(Long loanId);

    boolean existsByLoanIdAndActionAndPerformedByIgnoreCase(Long loanId, CreditAction action, String performedBy);

    /** A loan's latest entry of these kinds, such as its latest referral or resubmission, whichever came last. */
    Optional<CreditDecision> findFirstByLoanIdAndActionInOrderByIdDesc(Long loanId, Collection<CreditAction> actions);

    /** Decisions of these kinds made between two UTC instants, oldest first: the turnaround report's sample. */
    List<CreditDecision> findByActionInAndPerformedAtBetweenOrderByIdAsc(Collection<CreditAction> actions,
                                                                         LocalDateTime start, LocalDateTime end);

    /** One kind of entry for several loans, oldest first. */
    List<CreditDecision> findByLoanIdInAndActionOrderByPerformedAtAsc(Collection<Long> loanIds, CreditAction action);

    List<CreditDecision> findByLoanIdInOrderByIdAsc(Collection<Long> loanIds);
}
