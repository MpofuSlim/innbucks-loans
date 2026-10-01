package zw.co.innbucks.loans.core.staff.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

public interface StaffNotificationDispatchRepository extends JpaRepository<StaffNotificationDispatch, Long>,
        JpaSpecificationExecutor<StaffNotificationDispatch> {

    List<StaffNotificationDispatch> findByNotificationIdInOrderByIdAsc(Collection<Long> notificationIds);
}
