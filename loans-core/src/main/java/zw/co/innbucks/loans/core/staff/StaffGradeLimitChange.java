package zw.co.innbucks.loans.core.staff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One change to the Staff Grocery Loan grade-to-limit matrix (FR-SGL-009, FR-SGL-010): a grade's maximum loan amount
 * and score band from an effective date. Proposed by one user and approved or rejected by another; the database
 * refuses a decision by the proposer. Once decided it never changes again, except that an approved change not yet in
 * force can be SUPERSEDED by a later approval for the same grade and date.
 */
@Entity
@Table(name = "staff_grade_limit_changes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffGradeLimitChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The Paterson grade, upper case, e.g. C4. */
    @Column(name = "grade", length = 16, nullable = false)
    private String grade;

    /** The internal credit score / risk band label the grade maps to. */
    @Column(name = "score_band", length = 40, nullable = false)
    private String scoreBand;

    /** The most a staff member of this grade may borrow; zero means the grade is not lent to. */
    @Column(name = "maximum_limit", precision = 19, scale = 2, nullable = false)
    private BigDecimal maximumLimit;

    /** The market day from which this limit applies. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StaffGradeLimitChangeStatus status;

    @Column(name = "proposed_by", nullable = false)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private LocalDateTime proposedAt;

    @Column(name = "proposal_comment")
    private String proposalComment;

    /** Who approved or rejected it, or the proposer who withdrew it. */
    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_comment")
    private String decisionComment;

    /** The approved change that replaced this one, for a SUPERSEDED change. */
    @Column(name = "superseded_by")
    private Long supersededBy;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
