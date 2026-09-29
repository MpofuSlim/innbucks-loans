package zw.co.innbucks.loans.core.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Persisted saga instance — one per loan. The full transition history lives in
 * {@code audit_logs} (correlation_id = "SAGA-<loanId>"); this row holds the
 * current position and compensation bookkeeping.
 */
@Entity
@Table(name = "loan_saga", indexes = {
        @Index(name = "idx_loan_saga_loan_id", columnList = "loan_id", unique = true),
        @Index(name = "idx_loan_saga_state", columnList = "current_state")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanSaga implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false, unique = true)
    private Long loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", length = 40, nullable = false)
    private LoanSagaState currentState;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "compensated_at")
    private LocalDateTime compensatedAt;

    /**
     * The observed state last reported as an illegal transition from {@link #currentState}, so an
     * anomaly is logged and audited once rather than on every reconciler tick. A plain string, not
     * the enum: ddl-auto would pin the enum's values in a CHECK constraint it never widens.
     */
    @Column(name = "flagged_anomaly", length = 40)
    private String flaggedAnomaly;

    @Column(name = "last_transition_at", nullable = false)
    private LocalDateTime lastTransitionAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Optimistic lock — two reconciler ticks can never double-apply a transition. */
    @Version
    private Long version;
}
