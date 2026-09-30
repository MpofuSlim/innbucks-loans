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

/** How one loan left one checkpoint, and who decided (FR-SSB-014). One per checkpoint and loan; append-only. */
@Entity
@Table(name = "checkpoint_decisions")
@Getter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckpointDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stage_code", length = 40, nullable = false)
    private String stageCode;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    /** When the loan's wait at the checkpoint began, for its service level. */
    @Column(name = "entered_at", nullable = false)
    private LocalDateTime enteredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16, nullable = false)
    private CheckpointOutcome outcome;

    /** The credit reason code of a decline; null when cleared. */
    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "comment", nullable = false)
    private String comment;

    @Column(name = "decided_by", nullable = false)
    private String decidedBy;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;
}
