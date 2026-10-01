package zw.co.innbucks.loans.core.staff.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StaffNotificationBroadcastRepository extends JpaRepository<StaffNotificationBroadcast, Long> {

    Optional<StaffNotificationBroadcast> findByKind(StaffNotificationBroadcastKind kind);
}
