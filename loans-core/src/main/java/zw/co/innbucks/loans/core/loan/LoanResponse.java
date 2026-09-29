package zw.co.innbucks.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.ToString;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
    private String createdBy;
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
    private String alternateContactNumber;
    private String email;
    private String ecNumber;
    @ToString.Exclude
    private String nationalIdNumber;
    private Address address;
    private EmploymentDetail employmentDetail;
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

    // --- InnBucks booking and payout ---
    private LoanAccountStatus bookingStatus;
    private LoanDisbursementStatus disbursementStatus;
    private String disbursementStatusMessage;
    private LocalDateTime disbursedAt;
    private String disbursementReference;

    // --- Documents, base64 ---
    @ToString.Exclude
    private String signature;
    @ToString.Exclude
    private String nationalIdPicture;
    @ToString.Exclude
    private String payslipPicture;
}
