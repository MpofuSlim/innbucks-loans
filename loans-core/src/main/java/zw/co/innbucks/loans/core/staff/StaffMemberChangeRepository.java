package zw.co.innbucks.loans.core.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaffMemberChangeRepository extends JpaRepository<StaffMemberChange, Long> {

    List<StaffMemberChange> findByStaffMemberIdOrderByIdDesc(Long staffMemberId);
}
