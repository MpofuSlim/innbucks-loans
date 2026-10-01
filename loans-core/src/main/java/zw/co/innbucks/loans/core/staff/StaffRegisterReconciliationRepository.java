package zw.co.innbucks.loans.core.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffRegisterReconciliationRepository extends JpaRepository<StaffRegisterReconciliation, Long> {

    /** The latest reconciliation: the one the offer run's gate judges the register by. */
    Optional<StaffRegisterReconciliation> findFirstByOrderByIdDesc();
}
