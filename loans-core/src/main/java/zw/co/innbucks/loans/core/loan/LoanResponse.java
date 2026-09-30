package zw.co.innbucks.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.innbucks.loans.core.document.LoanDocumentSummary;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One loan in full: the applicant, the terms, where each stage stands, and the documents. The
 * stages run in order: SSB accepts the payroll deduction ({@code ssbApprovalStatus}), Credit
 * decides ({@code creditApprovalStatus}), InnBucks books the loan ({@code bookingStatus}), and the
 * payout lands ({@code disbursementStatus}).
 *
 * <p>Collections return {@link LoanSummaryResponse} instead, which carries no documents.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoanResponse implements Serializable {

    private Long id;
    /** The reference quoted to SSB and InnBucks. */
    private String reference;
    /** The human-facing reference (LN-2026-00042), assigned once the loan is booked. */
    private String publicReference;
    private LocalDateTime createdAt;
    /** The username of the officer or agent who originated the application (FR-SSB-017). */
    @Schema(description = "Username of the officer or agent who originated the application", example = "tmoyo")
    private String createdBy;
    /** Their name, for attribution and performance reporting. */
    @Schema(description = "Name of the officer or agent who originated the application", example = "Tendai Moyo")
    private String createdByName;
    /** The channel the application came through; absent for one captured in the portal. */
    @Schema(description = "The channel the application came through, as registered; absent for the portal",
            example = "superapp")
    private String channelId;
    @Schema(description = "The channel's name; absent for the portal", example = "InnBucks SuperApp")
    private String channelName;
    private String merchantCode;
    private String merchantName;

    // --- Applicant ---
    private String firstName;
    private String lastName;
    private LocalDate dateOfBirth;
    private Gender gender;
    private Title title;
    private MaritalStatus maritalStatus;
    private Integer numberOfDependants;
    private Integer numberOfChildren;
    private EducationLevel educationLevel;
    private String placeOfBirth;
    private String profession;
    private String mobileNumber;
    /** The InnBucks wallet the loan pays. */
    private String walletNumber;
    private String alternateContactNumber;
    private String email;
    private String ecNumber;
    @ToString.Exclude
    private String nationalIdNumber;
    private Address address;
    private EmploymentDetail employmentDetail;
    private List<PayslipDeduction> payslipDeductions;
    private NextOfKin nextOfKin;
    private Witness witness;
    @ToString.Exclude
    private BankingDetail bankingDetail;
    private LoanPurpose loanPurpose;
    private LineOfBusiness lineOfBusiness;

    // --- Terms ---
    private BigDecimal principal;
    private BigDecimal feeRate;
    private BigDecimal feeAmount;
    private BigDecimal interestRate;
    private BigDecimal interestAmount;
    private BigDecimal disbursedAmount;
    private int tenor;
    private BigDecimal monthlyInstallment;
    private BigDecimal grossedMonthlyDeduction;
    private BigDecimal commissionRate;
    private Boolean commissionPercentage;
    private BigDecimal agentCommissionRate;
    private BigDecimal agentCommission;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate repaymentStartDate;
    private LocalDate repaymentEndDate;

    /**
     * Where the application stands, in one word, for the applicant and the originator (FR-SSB-016); derived
     * from the four stage statuses below.
     */
    @Schema(description = "Where the application stands: RECEIVED, WITH_SSB, WITH_CREDIT, MORE_INFORMATION_NEEDED,"
            + " APPROVED, PAID, DECLINED, PAYOUT_DELAYED or NOT_COMPLETED", example = "WITH_CREDIT")
    private LoanStage stage;

    // --- SSB: the payroll deduction ---
    private LoanApprovalStatus ssbApprovalStatus;
    private String ssbStatusMessage;
    private LocalDateTime ssbStatusChangedAt;
    /** SSB's (Ndasenda's) id for the deduction, once it has answered. */
    private String ssbDeductionId;

    // --- Credit ---
    private InternalApprovalStatus creditApprovalStatus;
    private LocalDateTime creditDecisionAt;
    private String creditDecisionBy;
    private String creditDecisionComment;
    private String creditDecisionReasonCode;

    // --- Payslip review (FR-SSB-007): shown to lender-side staff only, see withoutPayslipReview ---
    private PayslipReviewStatus payslipReviewStatus;
    private String payslipReviewedBy;
    private LocalDateTime payslipReviewedAt;
    private String payslipReviewComment;

    // --- InnBucks booking and payout ---
    private LoanAccountStatus bookingStatus;
    private LoanDisbursementStatus disbursementStatus;
    private String disbursementStatusMessage;
    private LocalDateTime disbursedAt;
    private String disbursementReference;

    /**
     * The current version of each document, without content (FR-SSB-009): on GET /loans/{loanId}. The content,
     * and every earlier version, come from the loan's documents endpoints, which log each view.
     */
    private List<LoanDocumentSummary> documents;

    /**
     * This view without the payslip review, for a caller outside lender-side staff: telling an originator
     * their application is held for a fraud check would warn exactly the person the check may be about.
     */
    public LoanResponse withoutPayslipReview() {
        payslipReviewStatus = null;
        payslipReviewedBy = null;
        payslipReviewedAt = null;
        payslipReviewComment = null;
        return this;
    }
}
