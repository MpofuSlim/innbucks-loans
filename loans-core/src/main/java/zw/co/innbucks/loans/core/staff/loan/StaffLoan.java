package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffGrades;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A Staff Grocery Loan a borrower accepted in the SuperApp (FR-SGL-027): the terms they were shown and accepted, which
 * never change after, and where it stands. Who borrowed is kept as the register held them at acceptance.
 */
@Entity
@Table(name = "staff_loans")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "reference", "staffMemberId", "status"})
public class StaffLoan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SGL-2026-000143: the loan account its disbursement and voucher name. */
    @Column(name = "reference", length = 32, nullable = false, updatable = false)
    private String reference;

    @Column(name = "staff_member_id", nullable = false, updatable = false)
    private Long staffMemberId;

    /** The offer it took up. */
    @Column(name = "offer_id", nullable = false, updatable = false)
    private Long offerId;

    @Column(name = "employee_number", length = 32, nullable = false, updatable = false)
    private String employeeNumber;

    @Column(name = "full_name", length = 160, nullable = false, updatable = false)
    private String fullName;

    /** 2637XXXXXXXX, the number the voucher goes to. */
    @Column(name = "msisdn", length = 12, nullable = false, updatable = false)
    private String msisdn;

    @Column(name = "grade", length = StaffGrades.MAX_LENGTH, nullable = false, updatable = false)
    private String grade;

    /** The merchant it was accepted for: named in its agreement, paid the loan, and whose tills take its voucher. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false, updatable = false)
    private Merchant merchant;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    /** Percent; 0 for this product. */
    @Column(name = "interest_rate", precision = 9, scale = 4, nullable = false, updatable = false)
    private BigDecimal interestRate;

    @Column(name = "total_repayable", precision = 19, scale = 2, nullable = false, updatable = false)
    private BigDecimal totalRepayable;

    /** The market day it is collected from salary. */
    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "unredeemed_voucher_treatment", length = 32, nullable = false, updatable = false)
    private UnredeemedVoucherTreatment unredeemedVoucherTreatment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private StaffLoanStatus status;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private LocalDateTime acceptedAt;

    @Column(name = "disbursed_at")
    private LocalDateTime disbursedAt;

    @Column(name = "disbursement_reference", length = 64)
    private String disbursementReference;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    /**
     * Once paid out, the status the borrower moved to when they stopped being ACTIVE on the register (FR-SGL-007, BRD
     * 3.8); null while they are ACTIVE. With when, and the register batch that moved them.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "employment_flag", length = 16)
    private StaffEmploymentStatus employmentFlag;

    @Column(name = "employment_flagged_at")
    private LocalDateTime employmentFlaggedAt;

    @Column(name = "employment_flag_batch_id")
    private Long employmentFlagBatchId;

    /** Credit's arrears override it was accepted under, when the borrower owed a written-off loan (FR-SGL-014). */
    @Column(name = "arrears_override_id", updatable = false)
    private Long arrearsOverrideId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * Whether it counts as in arrears on {@code today} (FR-SGL-013, FR-SGL-014): disbursed and still owed more than
     * {@code graceDays} after its due date, or written off.
     */
    public boolean inArrearsOn(LocalDate today, int graceDays) {
        return status == StaffLoanStatus.WRITTEN_OFF
                || (status == StaffLoanStatus.DISBURSED && today.isAfter(dueDate.plusDays(graceDays)));
    }

    /** What is still owed, as far as loans knows: the core banking system holds the balance once it is disbursed. */
    public BigDecimal outstanding() {
        return switch (status) {
            case AWAITING_DISBURSEMENT, DISBURSED, WRITTEN_OFF -> totalRepayable;
            case REPAID, CANCELLED -> BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        };
    }

    /** Stops it before anything is paid. The caller checks it is AWAITING_DISBURSEMENT. */
    void cancel(LocalDateTime at, String by, String reason) {
        this.status = StaffLoanStatus.CANCELLED;
        this.cancelledAt = at;
        this.cancelledBy = by;
        this.cancellationReason = reason;
    }

    /** Writes it off with its balance still owed (FR-GEN-011). The caller checks it is DISBURSED. */
    void writeOff(LocalDateTime at) {
        this.status = StaffLoanStatus.WRITTEN_OFF;
        this.settledAt = at;
    }

    /** Puts back a loan written off in error: owed and being recovered again. The caller checks it is WRITTEN_OFF. */
    void reinstate() {
        this.status = StaffLoanStatus.DISBURSED;
        this.settledAt = null;
    }

    /** Records that its borrower is now {@code to}: flagged when not ACTIVE, the flag cleared when ACTIVE again. */
    void flagEmployment(StaffEmploymentStatus to, LocalDateTime at, Long batchId) {
        boolean cleared = to == StaffEmploymentStatus.ACTIVE;
        this.employmentFlag = cleared ? null : to;
        this.employmentFlaggedAt = cleared ? null : at;
        this.employmentFlagBatchId = cleared ? null : batchId;
    }
}
