package zw.co.reikan.loans.core.saga;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import zw.co.reikan.loans.core.loan.LoanStatusSnapshot;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LoanSagaRepository extends JpaRepository<LoanSaga, Long> {

    Optional<LoanSaga> findByLoanId(Long loanId);

    List<LoanSaga> findByCurrentStateNotIn(Collection<LoanSagaState> states);

    List<LoanSaga> findByCurrentState(LoanSagaState state);

    /**
     * The loans the reconciler looks at, as their status columns only (never the documents and
     * images on the row): every loan whose saga is still open, however old, plus loans created since
     * {@code since}, which may not have a saga yet.
     */
    @Query("""
            select new zw.co.reikan.loans.core.loan.LoanStatusSnapshot(l.id, l.loanApprovalStatus,
                   l.internalApprovalStatus, l.loanAccountStatus, l.disbursementStatus)
            from Loan l
            where l.createdDate >= :since
               or l.id in (select s.loanId from LoanSaga s where s.currentState not in :terminal)
            order by l.id
            """)
    List<LoanStatusSnapshot> findReconcileCandidates(@Param("since") LocalDateTime since,
                                                     @Param("terminal") Collection<LoanSagaState> terminal);

    /** The sagas of {@link #findReconcileCandidates}' loans: every open saga, and those of recent loans. */
    @Query("""
            select s from LoanSaga s
            where s.currentState not in :terminal
               or s.loanId in (select l.id from Loan l where l.createdDate >= :since)
            """)
    List<LoanSaga> findReconcileSagas(@Param("since") LocalDateTime since,
                                      @Param("terminal") Collection<LoanSagaState> terminal);
}
