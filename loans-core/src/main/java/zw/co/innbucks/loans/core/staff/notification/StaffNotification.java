package zw.co.innbucks.loans.core.staff.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One message to one staff member (FR-SGL-019, FR-SGL-020): its in-app copy, kept here as the member's inbox, and where
 * the message to their phone stands. Created at most once per offer and once per member per broadcast (FR-SGL-024).
 */
@Entity
@Table(name = "staff_notifications")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "staffMemberId", "template", "outboundStatus"})
public class StaffNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_member_id", nullable = false)
    private Long staffMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "template", length = 32, nullable = false)
    private StaffNotificationTemplate template;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    /** The offer it is about, for an offer notification. */
    @Column(name = "offer_id")
    private Long offerId;

    /** The run that issued that offer. */
    @Column(name = "run_id")
    private Long runId;

    /** The broadcast it belongs to, for a broadcast notification. */
    @Column(name = "broadcast_id")
    private Long broadcastId;

    @Column(name = "title", length = 120, nullable = false)
    private String title;

    @Column(name = "message", columnDefinition = "text", nullable = false)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outbound_status", length = 16, nullable = false)
    private StaffNotificationOutboundStatus outboundStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "skip_reason", length = 32)
    private StaffNotificationSkipReason skipReason;

    /** The channel that took the message, once SENT. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivered_channel", length = 16)
    private StaffNotificationChannel deliveredChannel;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /**
     * When the member first read it in the SuperApp. Written only by {@link StaffNotificationRepository#markRead} and
     * {@link StaffNotificationRepository#markAllRead}, never by saving this entity: the dispatcher saves it while it
     * sends, and must not write back a read time it loaded before the member read it.
     */
    @Column(name = "read_at", insertable = false, updatable = false)
    private LocalDateTime readAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Not sent to the member's phone, for {@code reason}. */
    void skip(StaffNotificationSkipReason reason, LocalDateTime at) {
        this.outboundStatus = StaffNotificationOutboundStatus.SKIPPED;
        this.skipReason = reason;
        this.finishedAt = at;
    }

    /** Sent on {@code channel}, or FAILED on every channel when {@code channel} is null. */
    void finish(StaffNotificationChannel channel, LocalDateTime at) {
        this.outboundStatus = channel == null ? StaffNotificationOutboundStatus.FAILED
                : StaffNotificationOutboundStatus.SENT;
        this.deliveredChannel = channel;
        this.finishedAt = at;
    }
}
