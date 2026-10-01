package zw.co.innbucks.loans.core.staff.loan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffLoanAgreementRepository extends JpaRepository<StaffLoanAgreement, Long> {

    Optional<StaffLoanAgreement> findByStaffLoanId(Long staffLoanId);
}
