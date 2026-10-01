package zw.co.innbucks.loans.core.staff.offer;

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
import lombok.ToString;
import zw.co.innbucks.loans.core.staff.StaffGrades;
import zw.co.innbucks.loans.core.staff.StaffMember;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Credit's authorised limit for one staff member in place of their grade's (FR-SGL-011), set under maker-checker. It
 * is bound to the grade the member held when it was proposed: a regrading brings back the matrix limit. 0 stops
 * offers to the member.
 */
@Entity
@Table(name = "staff_limit_overrides")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "staffMemberId", "grade", "status"})
public class StaffLimitOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_member_id", nullable = false)
    private Long staffMemberId;

    /** The grade the member held when it was proposed; it applies only while they still hold it. */
    @Column(name = "grade", length = StaffGrades.MAX_LENGTH, nullable = false)
    private String grade;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StaffLimitOverrideStatus status;

    @Column(name = "proposed_by", nullable = false)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private LocalDateTime proposedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_comment")
    private String decisionComment;

    @Column(name = "superseded_by")
    private Long supersededBy;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    @Column(name = "revoked_by")
    private String revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revocation_reason")
    private String revocationReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Whether it sets this member's offer now: APPROVED, and they still hold the grade it was set for. */
    public boolean appliesTo(StaffMember member) {
        return status == StaffLimitOverrideStatus.APPROVED && member.getId().equals(staffMemberId)
                && grade.equals(member.getGrade());
    }

    /** Whether it stops offers to the member altogether. */
    public boolean blocks() {
        return amount.signum() == 0;
    }
}
