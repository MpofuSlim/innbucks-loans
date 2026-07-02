package zw.co.reikan.loans.core.saga;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LoanSagaRepository extends JpaRepository<LoanSaga, Long> {

    Optional<LoanSaga> findByLoanId(Long loanId);

    List<LoanSaga> findByCurrentStateNotIn(Collection<LoanSagaState> states);

    List<LoanSaga> findByCurrentState(LoanSagaState state);
}
