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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A pre-approved Staff Grocery Loan offer to one member of the staff register (FR-SGL-016), issued by a weekly run, or
 * on demand when the borrower applies (FR-SGL-025), at their grade's limit and open until it expires (FR-SGL-018).
 * What it was based on is kept as it stood when issued, so a later grade or matrix change does not alter an offer
 * already made.
 */
@Entity
@Table(name = "staff_offers")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "staffMemberId", "cycleStart", "status"})
public class StaffOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_member_id", nullable = false)
    private Long staffMemberId;

    /** The run attempt that issued it; none for an offer made on demand. */
    @Column(name = "run_id")
    private Long runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 16, nullable = false)
    @Builder.Default
    private StaffOfferOrigin origin = StaffOfferOrigin.RUN;

    /** The Monday of the market week it was made in; one RUN offer per member per cycle. */
    @Column(name = "cycle_start", nullable = false)
    private LocalDate cycleStart;

    @Column(name = "grade", length = StaffGrades.MAX_LENGTH, nullable = false)
    private String grade;

    @Column(name = "score_band", length = 40, nullable = false)
    private String scoreBand;

    /** The approved grade-limit change the amount comes from. */
    @Column(name = "grade_limit_change_id", nullable = false)
    private Long gradeLimitChangeId;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** The limit override its amount came from, when Credit set one; otherwise it is the grade's limit. */
    @Column(name = "limit_override_id")
    private Long limitOverrideId;

    /** The earlier offer this one replaced while it was still open: a refresh. */
    @Column(name = "replaces_offer_id")
    private Long replacesOfferId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StaffOfferStatus status;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_reason")
    private String closedReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Ends the offer: no longer ACTIVE from {@code at}. */
    void close(StaffOfferStatus status, LocalDateTime at, String reason) {
        this.status = status;
        this.closedAt = at;
        this.closedReason = reason;
    }

    /**
     * Whether it can be taken up at {@code now}: ACTIVE and not past its expiry, which the weekly run may not yet have
     * closed.
     */
    public boolean isOpenAt(LocalDateTime now) {
        return status == StaffOfferStatus.ACTIVE && expiresAt.isAfter(now);
    }

    /**
     * Records that the offer became the loan {@code loanReference} at {@code at}.
     *
     * @throws IllegalStateException it was not open then: the caller checks {@link #isOpenAt} first
     */
    public void takeUp(LocalDateTime at, String loanReference) {
        if (!isOpenAt(at)) {
            throw new IllegalStateException("Offer " + id + " is " + status + " and cannot be taken up");
        }
        close(StaffOfferStatus.TAKEN_UP, at, "Taken up as " + loanReference);
    }
}
