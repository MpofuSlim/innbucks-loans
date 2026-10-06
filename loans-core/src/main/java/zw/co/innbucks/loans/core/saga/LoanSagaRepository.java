package zw.co.innbucks.loans.core.saga;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import zw.co.innbucks.loans.core.loan.LoanStatusSnapshot;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LoanSagaRepository extends JpaRepository<LoanSaga, Long> {

    Optional<LoanSaga> findByLoanId(Long loanId);

    List<LoanSaga> findByCurrentStateNotIn(Collection<LoanSagaState> states);

    List<LoanSaga> findByCurrentState(LoanSagaState state);

    /** The sagas in {@code state}, a chunk above {@code after} in id order (see {@code IdChunks}). */
    List<LoanSaga> findByCurrentStateAndIdGreaterThanOrderByIdAsc(LoanSagaState state, Long after, Pageable chunk);

    /** The sagas of these loans: a chunk of {@link #findReconcileCandidates}' loans. */
    List<LoanSaga> findByLoanIdIn(Collection<Long> loanIds);

    /**
     * The loans the reconciler looks at, as their status columns only (never the documents and
     * images on the row): every loan whose saga is still open, however old, plus loans created since
     * {@code since}, which may not have a saga yet. A chunk above {@code after}, in id order.
     */
    @Query("""
            select new zw.co.innbucks.loans.core.loan.LoanStatusSnapshot(l.id, l.loanApprovalStatus,
                   l.internalApprovalStatus, l.loanAccountStatus, l.disbursementStatus)
            from Loan l
            where (l.createdDate >= :since
                   or l.id in (select s.loanId from LoanSaga s where s.currentState not in :terminal))
              and l.id > :after
            order by l.id
            """)
    List<LoanStatusSnapshot> findReconcileCandidates(@Param("since") LocalDateTime since,
                                                     @Param("terminal") Collection<LoanSagaState> terminal,
                                                     @Param("after") long after, Pageable chunk);
}
