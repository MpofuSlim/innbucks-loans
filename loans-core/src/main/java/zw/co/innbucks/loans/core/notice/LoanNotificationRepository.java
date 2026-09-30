package zw.co.innbucks.loans.core.notice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoanNotificationRepository extends JpaRepository<LoanNotification, Long> {

    List<LoanNotification> findByLoanIdOrderByIdAsc(Long loanId);
}
