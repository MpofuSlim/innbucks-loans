package zw.co.innbucks.loans.core.staff.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * A member's choice about offer messages (FR-SGL-022). Opted out, they are sent no SMS or WhatsApp about offers or the
 * launch, but their offers are still made and still appear in their in-app inbox, so they can still apply. A member
 * without one receives the messages.
 */
@Entity
@Table(name = "staff_notification_preferences")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"staffMemberId", "offerMessagesOptedOut"})
public class StaffNotificationPreference {

    @Id
    @Column(name = "staff_member_id")
    private Long staffMemberId;

    @Column(name = "offer_messages_opted_out", nullable = false)
    private boolean offerMessagesOptedOut;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
