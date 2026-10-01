package zw.co.innbucks.loans.core.staff.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StaffNotificationRepository extends JpaRepository<StaffNotification, Long>,
        JpaSpecificationExecutor<StaffNotification> {

    /**
     * Claims a notification for sending: PENDING to SENDING, only if it is still PENDING.
     *
     * @return 1 when this caller now owns it, 0 when someone else claimed it first
     */
    @Modifying
    @Query("update StaffNotification n set n.outboundStatus = :sending, n.claimedAt = :at, n.version = n.version + 1"
            + " where n.id = :id and n.outboundStatus = :pending")
    int claim(@Param("id") Long id, @Param("pending") StaffNotificationOutboundStatus pending,
              @Param("sending") StaffNotificationOutboundStatus sending, @Param("at") LocalDateTime at);

    /** Notifications in this state after {@code after}, oldest first, ids only: one pass walks forward through them. */
    @Query("select n.id from StaffNotification n where n.outboundStatus = :status and n.id > :after order by n.id")
    List<Long> findIdsByOutboundStatusAfter(@Param("status") StaffNotificationOutboundStatus status,
                                            @Param("after") long after, Pageable pageable);

    long countByOutboundStatus(StaffNotificationOutboundStatus status);

    /** The member's latest notification of these templates in this state: their last offer message, when SENT. */
    Optional<StaffNotification> findFirstByStaffMemberIdAndOutboundStatusAndTemplateInOrderByIdDesc(
            Long staffMemberId, StaffNotificationOutboundStatus status, Collection<StaffNotificationTemplate> templates);
}
