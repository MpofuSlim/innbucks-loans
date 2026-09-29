package zw.co.innbucks.loans.core.loan;

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
 * One entry in a loan's credit decision log (FR-PBL-032): what was done, by whom, when, why, and the
 * loan data it was based on. The table is append-only in the database itself (a trigger refuses
 * UPDATE, DELETE and TRUNCATE), so an entry, once written with its decision, cannot be edited.
 */
@Entity
@Immutable
@Table(name = "credit_decisions")
@Getter
@ToString(exclude = "loanSnapshot")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 32, nullable = false)
    private CreditAction action;

    /** Null only on a resubmission, which answers a return rather than deciding. */
    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "comment", nullable = false)
    private String comment;

    @Column(name = "performed_by", nullable = false)
    private String performedBy;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    /** The loan as it stood when the action was taken: see {@link CreditDecisionSnapshot}. */
    @Column(name = "loan_snapshot", columnDefinition = "text", nullable = false)
    private String loanSnapshot;

    @Column(name = "snapshot_sha256", length = 64, nullable = false)
    private String snapshotSha256;
}
