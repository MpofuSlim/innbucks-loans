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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Credit's override of the arrears rule for one staff member (FR-SGL-014): with it, a written-off Staff Grocery Loan
 * balance no longer stops them taking a new loan. It never lifts an overdue loan, which is still open (FR-SGL-013).
 * Good for one loan, up to its last day.
 */
@Entity
@Table(name = "staff_arrears_overrides")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "staffMemberId", "validUntil", "status"})
public class StaffArrearsOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_member_id", nullable = false)
    private Long staffMemberId;

    @Column(name = "reason", nullable = false)
    private String reason;

    /** The last market day it may be used on. */
    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StaffArrearsOverrideStatus status;

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

    /** The loan the member took under it. */
    @Column(name = "staff_loan_id")
    private Long staffLoanId;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Whether it lets the member borrow on {@code today}: APPROVED, and not past its last day. */
    public boolean inForceOn(LocalDate today) {
        return status == StaffArrearsOverrideStatus.APPROVED && !today.isAfter(validUntil);
    }
}
