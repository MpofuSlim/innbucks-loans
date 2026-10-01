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

import java.time.LocalDateTime;

/**
 * A proposal to retire a grade from the grade-to-limit matrix, or to rename it (FR-SGL-010). Proposed by one user and
 * approved or rejected by another; the database refuses a decision by the proposer. Once decided it never changes.
 */
@Entity
@Table(name = "staff_grade_changes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffGradeChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 16, nullable = false)
    private StaffGradeChangeAction action;

    /** The grade retired or renamed, written as {@link StaffGrades} writes it. */
    @Column(name = "grade", length = StaffGrades.MAX_LENGTH, nullable = false)
    private String grade;

    /** The new name, for a RENAME. */
    @Column(name = "new_grade", length = StaffGrades.MAX_LENGTH)
    private String newGrade;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StaffGradeChangeStatus status;

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

    /** For an approved RENAME: the staff members moved to the new name. */
    @Column(name = "staff_members_moved")
    private Integer staffMembersMoved;

    /** For an approved RENAME: the pending and approved limit overrides moved to the new name. */
    @Column(name = "limit_overrides_moved")
    private Integer limitOverridesMoved;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
