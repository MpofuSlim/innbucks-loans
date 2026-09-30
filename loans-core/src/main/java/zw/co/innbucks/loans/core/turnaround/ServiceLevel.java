package zw.co.innbucks.loans.core.turnaround;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * The service level of one stage (FR-PBL-030), set by an administrator without a release: its target, past which
 * an item is overdue, and its escalation point, past which it is escalated. Wall-clock hours.
 */
@Entity
@Table(name = "service_levels")
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceLevel {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 32)
    private ServiceLevelStage stage;

    @Column(name = "target_hours", nullable = false)
    private int targetHours;

    @Column(name = "escalation_hours", nullable = false)
    private int escalationHours;

    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
