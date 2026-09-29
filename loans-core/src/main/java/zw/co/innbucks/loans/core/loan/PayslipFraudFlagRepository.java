package zw.co.innbucks.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PayslipFraudFlagRepository extends JpaRepository<PayslipFraudFlag, Long> {

    List<PayslipFraudFlag> findByLoanIdInOrderByIdAsc(Collection<Long> loanIds);
}
