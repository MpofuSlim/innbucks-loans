package zw.co.innbucks.loans.core.staff.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;

import java.util.Collection;

public interface StaffNotificationPreferenceRepository extends JpaRepository<StaffNotificationPreference, Long> {

    /** Members opted out of offer messages, among those whose employment status is not one of {@code excluded}. */
    @Query("select count(p) from StaffNotificationPreference p, StaffMember m where m.id = p.staffMemberId"
            + " and p.offerMessagesOptedOut = true and m.employmentStatus not in :excluded")
    long countOptedOut(@Param("excluded") Collection<StaffEmploymentStatus> excluded);
}
