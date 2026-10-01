package zw.co.innbucks.loans.core.staff.notification;

import org.springframework.data.domain.Page;
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

    /** A member's inbox, newest first. */
    Page<StaffNotification> findByStaffMemberIdOrderByIdDesc(Long staffMemberId, Pageable pageable);

    Optional<StaffNotification> findByIdAndStaffMemberId(Long id, Long staffMemberId);

    long countByStaffMemberIdAndReadAtIsNull(Long staffMemberId);

    /**
     * Marks one of the member's notifications read, unless it already is: the first read time is the one kept.
     *
     * @return 1 when it was unread, 0 when it was read already or is not theirs
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "update staff_notifications set read_at = :at where id = :id and staff_member_id = :staffMemberId"
            + " and read_at is null", nativeQuery = true)
    int markRead(@Param("id") Long id, @Param("staffMemberId") Long staffMemberId, @Param("at") LocalDateTime at);

    /** Marks every unread notification of the member read, in one statement; returns how many. */
    @Modifying(clearAutomatically = true)
    @Query(value = "update staff_notifications set read_at = :at where staff_member_id = :staffMemberId"
            + " and read_at is null", nativeQuery = true)
    int markAllRead(@Param("staffMemberId") Long staffMemberId, @Param("at") LocalDateTime at);

    /** The member's latest notification of these templates in this state: their last offer message, when SENT. */
    Optional<StaffNotification> findFirstByStaffMemberIdAndOutboundStatusAndTemplateInOrderByIdDesc(
            Long staffMemberId, StaffNotificationOutboundStatus status, Collection<StaffNotificationTemplate> templates);
}
