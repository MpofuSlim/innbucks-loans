package zw.co.innbucks.loans.core.loan;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;
import zw.co.innbucks.loans.core.channel.Channel;
import zw.co.innbucks.loans.core.disbursements.BookingFailureKind;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.user.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Table(name = "loans", indexes = {
        @Index(name = "idx_loans_batch_number", columnList = "batch_number"),
        @Index(name = "idx_loans_ec_number", columnList = "ec_number")
})
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Loan extends BaseEntity {

    @Column(name = "principal")
    private BigDecimal principal;

    @Column(name = "disbursed_amount")
    private BigDecimal disbursedAmount;

    @Column(name = "interest_rate")
    private BigDecimal interestRate;

    @Column(name = "interest_amount")
    private BigDecimal interestAmount;

    @Column(name = "fee_rate")
    private BigDecimal feeRate;

    @Column(name = "fee_amount")
    private BigDecimal feeAmount;

    @Column(name = "monthly_installment")
    private BigDecimal monthlyInstallment;

    @Column(name = "tenor")
    private int tenor;

    @Column(name = "loan_start_date")
    private LocalDate loanStartDate;

    @Column(name = "loan_end_date")
    private LocalDate loanEndDate;

    @Column(name = "mobile_number")
    private String mobileNumber;

    /**
     * The InnBucks wallet the loan pays (FR-SSB-003): the applicant's mobile number unless they gave
     * another. SMS still goes to {@link #mobileNumber}. Use {@link #payoutWalletNumber()} to pay.
     */
    @Column(name = "wallet_number")
    private String walletNumber;

    @Column(name = "ec_number")
    private String ecNumber;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @ToString.Exclude
    @Column(name = "national_id_number")
    private String nationalIdNumber;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "loan_status")
    private LoanApprovalStatus loanApprovalStatus;

    @Column(name = "loan_status_message")
    private String loanStatusMessage;

    @Column(name = "date_approved")
    private LocalDateTime dateApproved;

    @Enumerated(value = EnumType.STRING)
    @Column(name = "disbursement_status")
    private LoanDisbursementStatus disbursementStatus;

    @Column(name = "disbursement_status_message")
    private String disbursementStatusMessage;

    @Column(name = "date_disbursed")
    private LocalDateTime dateDisbursed;

    @Column(name = "disbursement_reference")
    private String disbursementReference;

    @Column(name = "disbursement_attempts")
    private Integer disbursementAttempts;

    @Column(name = "next_disbursement_attempt_date")
    private LocalDateTime nextDisbursementAttemptDate;

    @Column(name = "approval_reference")
    private String approvalReference;

    /** Lodgement attempts that provably never reached Ndasenda; see {@code NdasendaLodgementJob}. */
    @Column(name = "approval_attempt")
    private Integer approvalAttempt;

    /**
     * When the lodgement job claimed this loan to send its deduction to Ndasenda, committed BEFORE the
     * call so no other run or instance sends it too. Cleared only when that lodgement provably never
     * reached Ndasenda; otherwise it stays, as the time the deduction was sent. A NEW loan carrying one
     * is never sent again, so re-lodging a held loan (once Ndasenda confirms it never arrived) means
     * setting it back to NEW AND clearing this. New nullable columns rather than new status values,
     * whose CHECK constraint ddl-auto would never widen.
     */
    @Column(name = "lodgement_claimed_at")
    private LocalDateTime lodgementClaimedAt;

    /** Earliest time a lodgement that never reached Ndasenda is tried again. */
    @Column(name = "next_lodgement_attempt_at")
    private LocalDateTime nextLodgementAttemptAt;

    /**
     * When the booking job claimed this loan to book it with InnBucks, committed BEFORE the call so no
     * other run or instance books it too; InnBucks books AND pays on that call. Cleared only when the
     * booking provably never reached InnBucks. An account-PENDING loan still carrying one after the
     * stale-claim window belongs to a run that died mid-booking, so it is held for the inquiry job
     * rather than booked again.
     */
    @Column(name = "booking_claimed_at")
    private LocalDateTime bookingClaimedAt;

    @Column(name = "batch_number")
    private String batchNumber;

    /**
     * Human-facing sequential reference (LN-2026-00042), assigned by
     * {@link LoanPublicReferenceService}. Additive — the internal
     * {@link #getReference()} used by the Ndasenda integration is unchanged.
     */
    @Column(name = "public_reference", length = 20)
    private String publicReference;

    @Column(name = "commission_rate")
    private BigDecimal commissionRate;

    @Column(name = "grossed_monthly_deduction")
    private BigDecimal grossedMonthlyDeduction;

    @Column(name = "repayment_start_date")
    private LocalDate repaymentStartDate;

    @Column(name = "repayment_end_date")
    private LocalDate repaymentEndDate;

    @Column(name = "agent_commission_rate")
    private BigDecimal agentCommissionRate;

    @Column(name = "agent_commission")
    private BigDecimal agentCommission;

    @Column(name = "provider_commission_rate")
    private BigDecimal providerCommissionRate;

    @Column(name = "commission_rate_percentage")
    private Boolean commissionRatePercentage;

    @Column(name = "provider_commission")
    private BigDecimal providerCommission;

    @Column(name = "number_of_dependants")
    private Integer numberOfDependencies;

    @Column(name = "number_of_children")
    private Integer numberOfChildren;

    @Enumerated(EnumType.STRING)
    @Column(name = "education_level")
    private EducationLevel educationLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "marital_status")
    private MaritalStatus maritalStatus;

    @Column(name = "alternate_contact_number")
    private String alternateContactNumber;

    @Column(name = "place_of_birth")
    private String placeOfBirth;

    @Enumerated(EnumType.STRING)
    @Column(name = "title")
    private Title title;

    @Column(name = "email")
    private String email;

    @Embedded
    private Address address;

    @Embedded
    private EmploymentDetail employmentDetail;

    /**
     * The deductions already on the applicant's payslip, in the order captured (FR-SSB-006). LAZY: only
     * the detail view, the credit workbench, the credit decision snapshot and the fraud checks read them,
     * all inside a transaction, and for a page or batch of loans they load in one query rather than one
     * per loan.
     */
    @Builder.Default
    @BatchSize(size = 100)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ElementCollection
    @CollectionTable(name = "loan_payslip_deductions", joinColumns = @JoinColumn(name = "loan_id",
            foreignKey = @ForeignKey(name = "fk_loan_payslip_deductions_loan_id")))
    @OrderColumn(name = "line_number")
    private List<PayslipDeduction> payslipDeductions = new ArrayList<>();

    @Embedded
    private NextOfKin nextOfKin;

    @Embedded
    private Witness witness;

    @Enumerated(EnumType.STRING)
    @Column(name = "loan_account_status")
    private LoanAccountStatus loanAccountStatus;

    /**
     * Why the InnBucks pre-approved booking failed, when it did. Null on success and on
     * every loan booked before this was recorded — neither of which proves anything, so
     * only REFUSED makes a loan eligible for a manual recovery payout.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "booking_failure_kind")
    private BookingFailureKind bookingFailureKind;

    /**
     * When the InnBucks inquiry first answered that it holds no loan under this reference. Not
     * acted on automatically: InnBucks has not confirmed what its inquiry returns for a missing
     * loan, and marking a paid loan failed would open it to a second payout. It puts the loan in
     * front of an operator instead. Cleared if a later inquiry finds the loan.
     */
    @Column(name = "booking_not_found_at")
    private LocalDateTime bookingNotFoundAt;

    @Enumerated(EnumType.STRING)
    private LineOfBusiness lineOfBusiness;

    @Enumerated(EnumType.STRING)
    private LoanPurpose loanPurpose;

    @Column(name = "profession")
    private String profession;

    /**
     * LAZY, like {@link #createdByUser} and {@link #channel}: a loan read for its status (every job, every
     * lock) no longer drags in the merchant, the originator and the channel, with THEIR merchants, commission
     * groups, user groups and channel system users behind them. A read path that renders one fetches it in its
     * query or reads it inside its transaction; one that hands the loan past its transaction initialises what
     * the later step reads (see CLAUDE.md, "Loan's associations are LAZY").
     */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id")
    private Merchant merchant;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private Gender gender;

    @ToString.Exclude
    @Embedded
    private BankingDetail bankingDetail;

    @Column(name = "product_description")
    private String productDescription;

    @Column(name = "disbursement_merchant_account_number")
    private String disbursementMerchantAccountNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "internal_approval_status")
    private InternalApprovalStatus internalApprovalStatus;

    @Column(name = "internal_approval_date")
    private LocalDateTime internalApprovalDate;

    @Column(name = "internal_approval_by")
    private String internalApprovalBy;

    @Column(name = "internal_approval_comment")
    private String internalApprovalComment;

    /** The reason code given with the latest credit decision: see {@link CreditReasonCode}. */
    @Column(name = "internal_approval_reason_code")
    private String internalApprovalReasonCode;

    /**
     * When the originator last answered a return for more information, sending the loan back to Credit: its wait
     * for a decision is measured from then, or from SSB's approval ({@link #dateApproved}) if it was never returned
     * (FR-PBL-030).
     */
    @Column(name = "credit_resubmitted_at")
    private LocalDateTime creditResubmittedAt;

    /** The last payroll deduction: SSB's own end date for it when SSB gave one, else the loan's end date. */
    public LocalDate finalDeductionDate() {
        return repaymentEndDate != null ? repaymentEndDate : loanEndDate;
    }

    /** When the loan reached Credit for its current wait: its last resubmission, else SSB's approval. */
    public LocalDateTime creditQueueEnteredAt() {
        return creditResubmittedAt != null ? creditResubmittedAt : dateApproved;
    }

    /**
     * Where credit approved the money to go, frozen at approval: see {@link PayoutDestination}. Null on
     * a loan approved before the freeze existed, which still pays per the merchant's live settings.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approved_disbursement_type")
    private DisbursementType approvedDisbursementType;

    /** The merchant settlement account frozen at approval; null unless the loan pays a merchant. */
    @ToString.Exclude
    @Column(name = "approved_settlement_account")
    private String approvedSettlementAccount;

    /**
     * Set by {@link DeductionCancellationService} when a loan whose deduction was lodged with
     * Ndasenda will not be paid. New nullable columns rather than new values on an existing
     * status enum, whose CHECK constraint ddl-auto would never widen.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "deduction_cancellation_status")
    private DeductionCancellationStatus deductionCancellationStatus;

    /** One of the {@code DeductionCancellationService.REASON_*} codes; a string so a new one needs no DDL. */
    @Column(name = "deduction_cancellation_reason")
    private String deductionCancellationReason;

    @Column(name = "deduction_cancellation_requested_at")
    private LocalDateTime deductionCancellationRequestedAt;

    @Column(name = "deduction_cancellation_note")
    private String deductionCancellationNote;

    @Column(name = "deduction_cancelled_by")
    private String deductionCancelledBy;

    @Column(name = "deduction_cancelled_at")
    private LocalDateTime deductionCancelledAt;

    /**
     * When the response job reported this loan as waiting too long for Ndasenda's answer; null until
     * then. Kept so the alert is raised once rather than on every run.
     */
    @Column(name = "ndasenda_response_overdue_at")
    private LocalDateTime ndasendaResponseOverdueAt;

    @Column(name = "created_by")
    private String createdBy;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private User createdByUser;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private Channel channel;

    /**
     * The fingerprint of the current payslip (the SHA-256 of its bytes, see LoanDocument); null when there is
     * none. The payslip itself, and every earlier version, are in loan_documents.
     */
    @Column(name = "payslip_sha256", length = 64)
    private String payslipSha256;

    /**
     * Null unless something about the payslip raised a concern (FR-SSB-007). PENDING holds the loan back
     * from SSB until a reviewer clears or confirms it.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payslip_review_status", length = 32)
    private PayslipReviewStatus payslipReviewStatus;

    @Column(name = "payslip_reviewed_by")
    private String payslipReviewedBy;

    @Column(name = "payslip_reviewed_at")
    private LocalDateTime payslipReviewedAt;

    @Column(name = "payslip_review_comment")
    private String payslipReviewComment;

    /**
     * Where the customer's wallet payout goes. A loan captured before the wallet number existed was
     * given its mobile number by the V3 migration; the fallback keeps a row written by an older build
     * paying the number it has always paid.
     */
    public String payoutWalletNumber() {
        return walletNumber != null && !walletNumber.isBlank() ? walletNumber : mobileNumber;
    }

    public String getReference() {
        return referenceOf(getId());
    }

    /** The reference of the loan with this id, quoted to SSB and InnBucks and given to the applicant. */
    public static String referenceOf(Long loanId) {
        return String.format("%09d", loanId);
    }

    public Integer getNumberOfDependencies() {
        if (numberOfDependencies == null) {
            return 0;
        }
        return numberOfDependencies;
    }
}
