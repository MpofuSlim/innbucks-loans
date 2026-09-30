package zw.co.innbucks.loans.core.employment;

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

/**
 * What one employment event did to one loan (FR-SSB-024). A hold or a review stays OPEN in the officers' queue
 * until resolved, once; the database refuses any other change.
 */
@Entity
@Table(name = "loan_employment_events")
@Getter
@ToString(exclude = "comment")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanEmploymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 16, nullable = false)
    private LoanEmploymentEventAction action;

    /** Whether a decline of this application is sent to the applicant, as the treatment said when recorded. */
    @Column(name = "notify_on_decline", nullable = false)
    private boolean notifyOnDecline;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private LoanEmploymentEventStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 16)
    private LoanEmploymentEventOutcome outcome;

    @Column(name = "comment", length = 500)
    private String comment;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Closes an open hold or review with the officer's outcome. Saved by the caller. */
    void resolve(LoanEmploymentEventOutcome outcome, String comment, String resolvedBy, LocalDateTime resolvedAt) {
        this.status = LoanEmploymentEventStatus.CLOSED;
        this.outcome = outcome;
        this.comment = comment;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
    }
}
