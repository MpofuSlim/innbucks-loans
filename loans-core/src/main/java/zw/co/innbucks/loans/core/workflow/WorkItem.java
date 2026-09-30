package zw.co.innbucks.loans.core.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * One loan's wait at one stage, once someone has assigned or escalated it. The wait is identified by when it began,
 * so a loan that comes back to a stage starts a fresh item with no assignee and no escalation. Written only under
 * the loan's row lock.
 */
@Entity
@Table(name = "work_items")
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stage_code", length = 40, nullable = false)
    private String stageCode;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Column(name = "entered_at", nullable = false)
    private LocalDateTime enteredAt;

    @Column(name = "assigned_to", length = 100)
    private String assignedTo;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
