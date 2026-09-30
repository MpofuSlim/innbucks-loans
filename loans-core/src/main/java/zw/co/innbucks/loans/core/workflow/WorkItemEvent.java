package zw.co.innbucks.loans.core.workflow;

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

import java.time.LocalDateTime;

/** An assignment, reassignment, release or escalation of a work item, as it happened. Append-only by trigger. */
@Entity
@Table(name = "work_item_events")
@Getter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkItemEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "work_item_id", nullable = false)
    private Long workItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 16, nullable = false)
    private WorkItemAction action;

    @Column(name = "from_user", length = 100)
    private String fromUser;

    @Column(name = "to_user", length = 100)
    private String toUser;

    @Column(name = "performed_by", nullable = false)
    private String performedBy;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;
}
