package zw.co.innbucks.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import zw.co.innbucks.loans.core.MsisdnUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A loan application: the loan terms, the applicant and the documents.
 *
 * <p>Jackson MUST bind through the no-args constructor + setters, hence the {@code @JsonCreator}
 * on it. Without that, Jackson 3 picks Lombok's all-args constructor and passes {@code null} for
 * every key the body omits, which silently discarded the {@code amountType = NET_OF_FEES}
 * default: an application sent without it was priced GROSS of fees, so the customer received the
 * amount less the admin fee instead of the amount. Pinned by
 * {@code LoanApplicationWebContractTest.omittedAmountTypeDefaultsToNetOfFees}.
 *
 * <p>{@code toString} leaves out the national IDs, bank details and base64 documents, so logging
 * a request can never write them to the (long-retained) log files.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@ToString
public class LoanApplicationRequest implements Serializable {

    // --- Loan terms: all a quote needs (LoanQuoteRequest) ---

    @NotNull(message = "Loan amount is required")
    @Positive(message = "Loan amount must be greater than zero")
    @Schema(description = "What the customer receives (NET_OF_FEES) or borrows (GROSS_OF_FEES)", example = "500.00")
    private BigDecimal amount;

    @Builder.Default
    @Schema(description = "How to read amount; NET_OF_FEES when omitted", example = "NET_OF_FEES")
    private LoanAmountType amountType = LoanAmountType.NET_OF_FEES;

    @NotNull(message = "Loan tenor is required")
    @Positive(message = "Loan tenor must be greater than zero")
    @Schema(description = "Months", example = "12")
    private Integer tenor;

    // --- Applicant identity: always required ---

    @NotBlank(message = "EC number is required")
    @Schema(description = "Seven digits and a letter", example = "1234567A")
    private String ecNumber;

    @ToString.Exclude
    @NotBlank(message = "National ID number is required")
    @Schema(example = "63-1234567-A-42")
    private String nationalIdNumber;

    @NotBlank(message = "Mobile number is required")
    @Pattern(regexp = MsisdnUtils.ZIMBABWE_MOBILE_REGEX, message = MsisdnUtils.ZIMBABWE_MOBILE_MESSAGE)
    @Schema(example = "+263771234567")
    private String mobileNumber;

    /** The InnBucks wallet to pay; the mobile number when omitted. */
    @Pattern(regexp = MsisdnUtils.ZIMBABWE_MOBILE_REGEX, message = MsisdnUtils.ZIMBABWE_MOBILE_MESSAGE)
    @Schema(description = "InnBucks wallet the loan pays; the mobile number when omitted", example = "+263771234567")
    private String walletNumber;

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    @Schema(example = "1988-04-12")
    private LocalDate dateOfBirth;

    // --- Required for an application (LoanApplicationChecks) ---

    @NotBlank(groups = LoanApplicationChecks.class, message = "First name is required")
    @Schema(example = "Tendai")
    private String firstName;

    @NotBlank(groups = LoanApplicationChecks.class, message = "Last name is required")
    @Schema(example = "Moyo")
    private String lastName;

    @NotNull(groups = LoanApplicationChecks.class, message = "Marital status is required")
    private MaritalStatus maritalStatus;

    @NotBlank(groups = LoanApplicationChecks.class, message = "Place of birth is required")
    @Schema(example = "Gweru")
    private String placeOfBirth;

    @Valid
    @NotNull(groups = LoanApplicationChecks.class, message = "Address is required")
    private Address address;

    @Valid
    @NotNull(groups = LoanApplicationChecks.class, message = "Employment detail is required")
    private EmploymentDetail employmentDetail;

    /** The deductions already on the payslip, each with who it is paid to (FR-SSB-006). May be empty. */
    @Valid
    @Size(max = 30, message = "At most 30 payslip deductions")
    private List<PayslipDeduction> payslipDeductions;

    @Valid
    @NotNull(groups = LoanApplicationChecks.class, message = "Next of kin is required")
    private NextOfKin nextOfKin;

    @NotNull(groups = LoanApplicationChecks.class, message = "Loan purpose is required")
    private LoanPurpose loanPurpose;

    /**
     * Sent to InnBucks as {@code businessLine}. Takes the enum NAME ({@code SERVICES}); the
     * description ({@code "Services"}) is what goes on the wire to InnBucks.
     */
    @NotNull(groups = LoanApplicationChecks.class, message = "Line of business is required")
    private LineOfBusiness lineOfBusiness;

    // --- Optional ---

    private Title title;
    private Gender gender;
    @PositiveOrZero(message = "Number of dependants cannot be negative")
    private Integer numberOfDependants;
    @PositiveOrZero(message = "Number of children cannot be negative")
    private Integer numberOfChildren;
    private EducationLevel educationLevel;
    private String profession;
    private String alternateContactNumber;
    private String email;
    private Witness witness;
    @ToString.Exclude
    private BankingDetail bankingDetail;

    /** The channel that captured the application; absent for one captured in the portal by the signed-in user. */
    private String channelId;

    // --- Signing (FR-SSB-013): the versions of each instrument the applicant accepted ---

    /** The loan agreement version the applicant read and accepted; required once one is published. */
    @Positive(message = "Loan agreement version must be greater than zero")
    @Schema(description = "The loan agreement version the applicant accepted, from POST /loans/instruments/preview;"
            + " required once one is published", example = "3")
    private Integer loanAgreementVersion;

    /** The SSB deduction authority version the applicant read and accepted; required once one is published. */
    @Positive(message = "Deduction authority version must be greater than zero")
    @Schema(description = "The SSB deduction authority version the applicant accepted, from POST"
            + " /loans/instruments/preview; required once one is published", example = "2")
    private Integer deductionAuthorityVersion;

    // --- Documents, base64 ---

    @ToString.Exclude
    private String signature;
    @ToString.Exclude
    private String nationalIdPicture;
    @ToString.Exclude
    private String payslipPicture;

    /** The terms alone, which is what pricing reads. */
    public LoanQuoteRequest quoteRequest() {
        return new LoanQuoteRequest(amount, amountType, tenor);
    }
}
