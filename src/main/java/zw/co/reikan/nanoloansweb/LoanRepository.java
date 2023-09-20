package zw.co.reikan.nanoloansweb;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LoanRepository extends JpaRepository<Loan, Long> {
    Optional<Loan> findByEcNumberAndLoanStatus(String ecNumber, LoanStatus loanStatus);
}