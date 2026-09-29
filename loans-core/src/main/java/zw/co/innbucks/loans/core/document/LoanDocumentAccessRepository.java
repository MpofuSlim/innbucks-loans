package zw.co.innbucks.loans.core.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanDocumentAccessRepository extends JpaRepository<LoanDocumentAccess, Long> {

    List<LoanDocumentAccess> findByLoanIdOrderByIdDesc(Long loanId);
}
