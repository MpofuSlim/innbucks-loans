package zw.co.innbucks.loans.core.loan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CreditReasonCodeRepository extends JpaRepository<CreditReasonCode, String> {

    List<CreditReasonCode> findByActiveTrueOrderByDecisionAscDisplayOrderAsc();

    List<CreditReasonCode> findByActiveTrueAndDecisionOrderByDisplayOrderAsc(InternalApprovalStatus decision);
}
