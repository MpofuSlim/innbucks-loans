package zw.co.innbucks.loans.core.staff.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * One attempt to reach a staff member on one channel (FR-SGL-023): who, on which channel, with which template and
 * version, when, and what happened. Never changed once written.
 */
@Entity
@Immutable
@Table(name = "staff_notification_dispatches")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "recipient")
public class StaffNotificationDispatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "staff_member_id", nullable = false)
    private Long staffMemberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 16, nullable = false)
    private StaffNotificationChannel channel;

    /** The member's mobile number, in E.164: their SMS and WhatsApp number, and the number their SuperApp account is. */
    @Column(name = "recipient", length = 32, nullable = false)
    private String recipient;

    @Enumerated(EnumType.STRING)
    @Column(name = "template", length = 32, nullable = false)
    private StaffNotificationTemplate template;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StaffNotificationDispatchStatus status;

    /** Our reference for an SMS at the notification API. */
    @Column(name = "gateway_reference", length = 64)
    private String gatewayReference;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
