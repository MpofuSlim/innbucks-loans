package zw.co.reikan.loans.core.commission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommissionGroupRepository extends JpaRepository<CommissionGroup, Long> {
    List<CommissionGroup> findCommissionGroupByEnabled(boolean enabled);
}
