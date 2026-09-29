package zw.co.reikan.loans.core.loan;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import zw.co.reikan.loans.core.channel.Channel;
import zw.co.reikan.loans.core.disbursements.BookingFailureKind;
import zw.co.reikan.loans.core.disbursements.LoanAccountStatus;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.user.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Table(name = "loan_request", indexes = {
        @Index(name = "idx_loan_request_batch_number", columnList = "batch_number"),
        @Index(name = "idx_ec_number", columnList = "ec_number")
})
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Loan extends BaseEntity {

    @Column(name = "principal")
    private BigDecimal principal;

    @Column(name = "disburse_amount")
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

    @ToString.Exclude
    @Lob
    @Column(name = "signature", columnDefinition = "text")
    private String signature;

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

    /** Lodgement attempts that provably never reached Ndasenda; see {@code LoanApprovalServiceJob}. */
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

    @Column(name = "number_of_dependencies")
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

    @ManyToOne
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

    @ManyToOne
    @JoinColumn(name = "created_by_user_id")
    private User createdByUser;

    @ManyToOne
    @JoinColumn(name = "agent_id")
    private User agent;

    @ManyToOne
    @JoinColumn(name = "channel_id")
    private Channel channel;

    @ToString.Exclude
    @Lob
    @Column(name = "national_id_picture", columnDefinition = "text")
    private String nationalIdPicture;

    @ToString.Exclude
    @Lob
    @Column(name = "payslip_picture", columnDefinition = "text")
    private String payslipPicture;

    public String getReference() {
        return String.format("%09d", getId());
    }

    public Integer getNumberOfDependencies() {
        if (numberOfDependencies == null) {
            return 0;
        }
        return numberOfDependencies;
    }
}
