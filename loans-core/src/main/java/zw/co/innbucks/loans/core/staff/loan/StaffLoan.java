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
import java.util.Arrays;

/**
 * A Staff Grocery Loan a borrower accepted in the SuperApp (FR-SGL-027): the terms they were shown and accepted, which
 * never change after, and where it stands. Who borrowed is kept as the register held them at acceptance.
 *
 * <p>How it is paid out ({@link #payoutMethod}), and for a cash loan where ({@link #payoutDestination},
 * {@link #payoutAccount}), is decided at acceptance and never changes. A cash loan's payout moves only through the
 * methods below, each of which refuses a move its payout is not in a state for. Every payout column is written only on
 * an entity loaded by {@link StaffLoanRepository#lockById} in the same transaction, or by a conditional UPDATE: the bank
 * books and pays on one call, so two writers deciding on the same payout could pay it twice.
 */
@Entity
@Table(name = "staff_loans")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(of = {"id", "reference", "staffMemberId", "status", "payoutMethod", "payoutStatus"})
public class StaffLoan {

    /** The longest {@link #payoutMessage} kept; a longer one is cut. */
    public static final int MESSAGE_LENGTH = 500;
    /** The longest {@link #payoutLastAnswer} kept; a longer one is cut. */
    public static final int ANSWER_LENGTH = 2000;
    static final int BANK_STATUS_LENGTH = 64;
    static final int BANK_LOAN_ACCOUNT_LENGTH = 64;

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

    /**
     * 2637XXXXXXXX, the borrower's registered phone: the number the voucher goes to, and the wallet a cash loan is paid
     * into when its destination is VEENGU_WALLET.
     */
    @Column(name = "msisdn", length = 12, nullable = false, updatable = false)
    private String msisdn;

    @Column(name = "grade", length = StaffGrades.MAX_LENGTH, nullable = false, updatable = false)
    private String grade;

    /**
     * The merchant it was accepted for: for a voucher loan, named in its agreement, paid the loan, and whose tills take
     * its voucher. Recorded, and not used, for a cash loan.
     */
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

    /** VOUCHER, or CASH: paid into the borrower's own account by the bank. Decided at acceptance. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payout_method", length = 16, nullable = false, updatable = false)
    @Builder.Default
    private StaffLoanPayoutMethod payoutMethod = StaffLoanPayoutMethod.VOUCHER;

    /** Where a cash loan is paid, as at acceptance; null for a voucher loan. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payout_destination", length = 16, updatable = false)
    private StaffLoanPayoutDestination payoutDestination;

    /** The wallet (2637XXXXXXXX) or account number a cash loan is paid into, as at acceptance. Never printed. */
    @Column(name = "payout_account", length = 20, updatable = false)
    private String payoutAccount;

    /** Where a cash loan's payout stands; null for a voucher loan. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payout_status", length = 16)
    private StaffLoanPayoutStatus payoutStatus;

    /** When it was claimed for sending; set only while it is SENDING. */
    @Column(name = "payout_claimed_at")
    private LocalDateTime payoutClaimedAt;

    /** Why the payout stands where it does, for the operators. */
    @Column(name = "payout_message", length = MESSAGE_LENGTH)
    private String payoutMessage;

    /** The first time the bank's inquiry said it holds no loan under this reference; cleared when it finds it. */
    @Column(name = "payout_not_found_at")
    private LocalDateTime payoutNotFoundAt;

    /** The first time the bank said it holds it (a 2xx apply, or a found inquiry). Never cleared. */
    @Column(name = "payout_bank_seen_at")
    private LocalDateTime payoutBankSeenAt;

    /** The bank's own status of the booking, from its last inquiry answer. */
    @Column(name = "payout_bank_status", length = BANK_STATUS_LENGTH)
    private String payoutBankStatus;

    /** The bank's last answer to the apply or the inquiry, redacted and cut short. Never printed. */
    @Column(name = "payout_last_answer", length = ANSWER_LENGTH)
    private String payoutLastAnswer;

    /**
     * The bank's answer to the last booking sent (the apply), redacted and cut short, kept when the inquiry's answers
     * replace {@link #payoutLastAnswer}. Never printed.
     */
    @Column(name = "payout_apply_answer", length = ANSWER_LENGTH)
    private String payoutApplyAnswer;

    /** When the bank last answered its inquiry about it, holding it (000) or not (004). */
    @Column(name = "payout_checked_at")
    private LocalDateTime payoutCheckedAt;

    /**
     * The first time the bank's inquiry held it in a status that says it did not pay it ({@code failed-statuses}, e.g.
     * REJECTED); cleared when it holds it in any other status.
     */
    @Column(name = "payout_declined_at")
    private LocalDateTime payoutDeclinedAt;

    /** When a person confirmed with the bank that it was never booked. */
    @Column(name = "payout_failed_at")
    private LocalDateTime payoutFailedAt;

    /** When the bank said, after that, that it does hold it: the borrower may have been paid for a cancelled loan. */
    @Column(name = "payout_paid_after_cancel_at")
    private LocalDateTime payoutPaidAfterCancelAt;

    /** The bank's own loan account, from the inquiry, when it names one. */
    @Column(name = "bank_loan_account", length = BANK_LOAN_ACCOUNT_LENGTH)
    private String bankLoanAccount;

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

    /**
     * Stops it before anything is paid. The caller checks it is AWAITING_DISBURSEMENT. A cash loan's payout is
     * WITHDRAWN with it, and only one nothing was booked for (READY or REFUSED) can be.
     *
     * @throws IllegalStateException a cash loan the bank may have booked
     */
    void cancel(LocalDateTime at, String by, String reason) {
        if (payoutMethod == StaffLoanPayoutMethod.CASH) {
            requirePayout("cancel", StaffLoanPayoutStatus.READY, StaffLoanPayoutStatus.REFUSED);
            this.payoutStatus = StaffLoanPayoutStatus.WITHDRAWN;
        }
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

    /** Whether the money may already be on its way: a cash payout SENDING, BOOKED or IN_DOUBT. */
    public boolean payoutInFlight() {
        return payoutStatus != null && payoutStatus.inFlight();
    }

    /** Claims a READY payout for sending: from here it is never sent again. */
    void claimPayout(LocalDateTime at) {
        requirePayout("claim", StaffLoanPayoutStatus.READY);
        this.payoutStatus = StaffLoanPayoutStatus.SENDING;
        this.payoutClaimedAt = at;
        this.payoutMessage = null;
    }

    /** Leaves a READY payout unsent, saying why (a guard refused to send it). */
    void holdPayout(String message) {
        requirePayout("hold", StaffLoanPayoutStatus.READY);
        this.payoutMessage = cut(message, MESSAGE_LENGTH);
    }

    /** The claim was released because the payout provably never reached the bank: READY again. */
    void releasePayoutClaim(String message, String answer) {
        requirePayout("release", StaffLoanPayoutStatus.SENDING);
        this.payoutStatus = StaffLoanPayoutStatus.READY;
        this.payoutClaimedAt = null;
        this.payoutMessage = cut(message, MESSAGE_LENGTH);
        this.payoutLastAnswer = cut(answer, ANSWER_LENGTH);
    }

    /** Keeps the bank's answer to the booking just sent, apart from the inquiry's answers. */
    void payoutApplyAnswered(String answer) {
        requirePayout("record the booking's answer for", StaffLoanPayoutStatus.SENDING);
        this.payoutApplyAnswer = cut(answer, ANSWER_LENGTH);
    }

    /** The bank took it (a 2xx whose envelope says so, or has none). */
    void payoutBooked(LocalDateTime at, String answer) {
        requirePayout("book", StaffLoanPayoutStatus.SENDING);
        this.payoutStatus = StaffLoanPayoutStatus.BOOKED;
        this.payoutClaimedAt = null;
        this.payoutMessage = null;
        this.payoutLastAnswer = cut(answer, ANSWER_LENGTH);
        seenByBank(at);
    }

    /**
     * The bank took it (a 2xx) after its claim was held IN_DOUBT as abandoned, or after its inquiry already said so:
     * BOOKED, and seen by the bank for good. Its answer to the booking is kept.
     */
    void payoutBookedLate(LocalDateTime at, String answer) {
        requirePayout("book", StaffLoanPayoutStatus.IN_DOUBT, StaffLoanPayoutStatus.BOOKED);
        if (payoutStatus == StaffLoanPayoutStatus.IN_DOUBT) {
            this.payoutStatus = StaffLoanPayoutStatus.BOOKED;
            this.payoutMessage = null;
        }
        this.payoutApplyAnswer = cut(answer, ANSWER_LENGTH);
        seenByBank(at);
    }

    /** It may or may not have been booked: held for the inquiry, never sent again. */
    void payoutInDoubt(String message, String answer) {
        requirePayout("hold in doubt", StaffLoanPayoutStatus.SENDING);
        this.payoutStatus = StaffLoanPayoutStatus.IN_DOUBT;
        this.payoutClaimedAt = null;
        this.payoutMessage = cut(message, MESSAGE_LENGTH);
        this.payoutLastAnswer = cut(answer, ANSWER_LENGTH);
    }

    /** The bank answered that it did not take it: nothing was booked. The loan stays open for a person to decide. */
    void payoutRefused(String message, String answer) {
        requirePayout("refuse", StaffLoanPayoutStatus.SENDING);
        this.payoutStatus = StaffLoanPayoutStatus.REFUSED;
        this.payoutClaimedAt = null;
        this.payoutMessage = cut(message, MESSAGE_LENGTH);
        this.payoutLastAnswer = cut(answer, ANSWER_LENGTH);
    }

    /** A person sends a refused payout again, under the same reference: a refusal proves it was never booked. */
    void sendRefusedPayoutAgain() {
        requirePayout("send again", StaffLoanPayoutStatus.REFUSED);
        this.payoutStatus = StaffLoanPayoutStatus.READY;
        this.payoutMessage = null;
    }

    /**
     * The bank's inquiry found it, in {@code bankStatus}, not (yet) paid: an IN_DOUBT payout is BOOKED after all. Clears
     * an earlier "not found", and an earlier "declined".
     */
    void payoutFound(LocalDateTime at, String bankStatus, String answer) {
        heldByBank(at, bankStatus, answer);
        this.payoutDeclinedAt = null;
    }

    /**
     * The bank's inquiry found it in {@code bankStatus}, a status that says it did not pay it: held as
     * {@link #payoutFound}, and when it first said so is kept, so a person can record it as never paid once it has said
     * so for long enough.
     *
     * @return whether this is the first time it said so
     */
    boolean payoutDeclined(LocalDateTime at, String bankStatus, String answer) {
        heldByBank(at, bankStatus, answer);
        if (payoutDeclinedAt != null) {
            return false;
        }
        this.payoutDeclinedAt = at;
        return true;
    }

    private void heldByBank(LocalDateTime at, String bankStatus, String answer) {
        requirePayout("record the bank's answer for", StaffLoanPayoutStatus.BOOKED, StaffLoanPayoutStatus.IN_DOUBT);
        this.payoutStatus = StaffLoanPayoutStatus.BOOKED;
        this.payoutNotFoundAt = null;
        this.payoutBankStatus = cut(bankStatus, BANK_STATUS_LENGTH);
        this.payoutLastAnswer = cut(answer, ANSWER_LENGTH);
        this.payoutCheckedAt = at;
        seenByBank(at);
    }

    /**
     * The bank's inquiry says it holds no loan under this reference. Changes nothing else: an answer like that never
     * fails a payout by itself.
     *
     * @return whether this is the first time it said so
     */
    boolean payoutNotFound(LocalDateTime at, String answer) {
        requirePayout("record the bank's answer for", StaffLoanPayoutStatus.BOOKED, StaffLoanPayoutStatus.IN_DOUBT);
        this.payoutLastAnswer = cut(answer, ANSWER_LENGTH);
        this.payoutCheckedAt = at;
        if (payoutNotFoundAt != null) {
            return false;
        }
        this.payoutNotFoundAt = at;
        return true;
    }

    /**
     * The money is in the borrower's account: PAID, seen by the bank, and the loan DISBURSED under its own reference.
     *
     * @param bankLoanAccount the bank's own loan account; null keeps the one already recorded
     */
    void paidOut(LocalDateTime at, String bankLoanAccount) {
        requirePayout("pay out", StaffLoanPayoutStatus.BOOKED, StaffLoanPayoutStatus.IN_DOUBT);
        this.payoutStatus = StaffLoanPayoutStatus.PAID;
        this.payoutNotFoundAt = null;
        this.payoutDeclinedAt = null;
        this.payoutMessage = null;
        seenByBank(at);
        if (bankLoanAccount != null && !bankLoanAccount.isBlank()) {
            this.bankLoanAccount = cut(bankLoanAccount.strip(), BANK_LOAN_ACCOUNT_LENGTH);
        }
        this.status = StaffLoanStatus.DISBURSED;
        this.disbursedAt = at;
        this.disbursementReference = reference;
    }

    /** Notes why a cash payout stands where it does, changing nothing else. */
    void notePayout(String message) {
        if (payoutMethod != StaffLoanPayoutMethod.CASH) {
            throw new IllegalStateException("Staff loan " + reference + " is a voucher loan: it has no payout");
        }
        this.payoutMessage = cut(message, MESSAGE_LENGTH);
    }

    /**
     * Whether a person may now confirm it was never paid, on the bank's word, either way:
     * <ul>
     *   <li><b>missing</b>: IN_DOUBT, the bank never said it holds it, and its inquiry said it holds no loan under this
     *       reference no later than {@code evidenceBy}, and has said nothing else since;</li>
     *   <li><b>declined</b>: BOOKED or IN_DOUBT, and its inquiry has held it in a status that says it did not pay it
     *       since no later than {@code evidenceBy}, and in no other status since.</li>
     * </ul>
     * Either way the inquiry must have answered at or after {@code answeredSince}: a "not found" or "declined" that
     * the bank has not repeated lately is no evidence that it still holds.
     */
    public boolean mayConfirmNotBooked(LocalDateTime evidenceBy, LocalDateTime answeredSince) {
        if (payoutMethod != StaffLoanPayoutMethod.CASH || status != StaffLoanStatus.AWAITING_DISBURSEMENT
                || payoutCheckedAt == null || payoutCheckedAt.isBefore(answeredSince)) {
            return false;
        }
        return reportedMissingSince(evidenceBy) || reportedDeclinedSince(evidenceBy);
    }

    /** IN_DOUBT, never seen by the bank, and reported missing since no later than {@code by}. */
    public boolean reportedMissingSince(LocalDateTime by) {
        return payoutMethod == StaffLoanPayoutMethod.CASH && payoutStatus == StaffLoanPayoutStatus.IN_DOUBT
                && payoutBankSeenAt == null && payoutNotFoundAt != null && !payoutNotFoundAt.isAfter(by);
    }

    /** BOOKED or IN_DOUBT, and held by the bank in a status that says it did not pay it since no later than {@code by}. */
    public boolean reportedDeclinedSince(LocalDateTime by) {
        return payoutMethod == StaffLoanPayoutMethod.CASH && payoutStatus != null && payoutStatus.awaitsInquiry()
                && payoutDeclinedAt != null && !payoutDeclinedAt.isAfter(by);
    }

    /**
     * A person confirmed with the bank that it was never paid: FAILED, and the loan cancelled.
     *
     * @throws IllegalStateException unless {@link #mayConfirmNotBooked} holds
     */
    void payoutNotBooked(LocalDateTime at, LocalDateTime evidenceBy, LocalDateTime answeredSince, String by,
                         String reason) {
        if (!mayConfirmNotBooked(evidenceBy, answeredSince)) {
            throw new IllegalStateException("Staff loan " + reference + ": a payout that is " + describePayout()
                    + (payoutBankSeenAt != null ? ", seen by the bank," : "")
                    + (payoutNotFoundAt == null && payoutDeclinedAt == null ? " and never reported missing or"
                    + " declined" : "") + " cannot be confirmed not booked");
        }
        this.payoutStatus = StaffLoanPayoutStatus.FAILED;
        this.payoutFailedAt = at;
        this.payoutMessage = cut(reason, MESSAGE_LENGTH);
        this.status = StaffLoanStatus.CANCELLED;
        this.cancelledAt = at;
        this.cancelledBy = by;
        this.cancellationReason = reason;
    }

    /**
     * The bank says it holds a payout a person had confirmed was never booked: the borrower may have been paid for a
     * cancelled loan. Recorded once; the loan stays cancelled.
     *
     * @return whether this is the first time
     */
    boolean payoutPaidAfterCancel(LocalDateTime at, String bankStatus, String answer) {
        if (payoutMethod != StaffLoanPayoutMethod.CASH || payoutStatus != StaffLoanPayoutStatus.FAILED) {
            throw new IllegalStateException("Staff loan " + reference + ": a payout that is " + describePayout()
                    + " was not confirmed not booked");
        }
        this.payoutBankStatus = cut(bankStatus, BANK_STATUS_LENGTH);
        this.payoutLastAnswer = cut(answer, ANSWER_LENGTH);
        seenByBank(at);
        if (payoutPaidAfterCancelAt != null) {
            return false;
        }
        this.payoutPaidAfterCancelAt = at;
        return true;
    }

    private void seenByBank(LocalDateTime at) {
        if (payoutBankSeenAt == null) {
            this.payoutBankSeenAt = at;
        }
    }

    /** A cash loan awaiting disbursement whose payout is one of {@code from}, else refused. */
    private void requirePayout(String move, StaffLoanPayoutStatus... from) {
        if (payoutMethod != StaffLoanPayoutMethod.CASH || status != StaffLoanStatus.AWAITING_DISBURSEMENT
                || payoutStatus == null || !Arrays.asList(from).contains(payoutStatus)) {
            throw new IllegalStateException("Staff loan " + reference + ": cannot " + move + " a payout that is "
                    + describePayout() + "; only one " + Arrays.toString(from) + " awaiting disbursement");
        }
    }

    private String describePayout() {
        return payoutMethod == StaffLoanPayoutMethod.CASH ? payoutStatus + " (loan " + status + ")"
                : "none (a voucher loan)";
    }

    private static String cut(String text, int length) {
        return text == null ? null : text.length() <= length ? text : text.substring(0, length);
    }
}
