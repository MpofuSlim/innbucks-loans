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

/** A message to the whole staff register, sent once (FR-SGL-020): who started it, and how many it was for. */
@Entity
@Immutable
@Table(name = "staff_notification_broadcasts")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class StaffNotificationBroadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 16, nullable = false)
    private StaffNotificationBroadcastKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "template", length = 32, nullable = false)
    private StaffNotificationTemplate template;

    @Column(name = "template_version", nullable = false)
    private int templateVersion;

    /** Members of the register it was for. */
    @Column(name = "recipients", nullable = false)
    private int recipients;

    /** Members of the register left out because they have left the bank. */
    @Column(name = "left_excluded", nullable = false)
    private int leftExcluded;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
