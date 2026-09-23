package zw.co.reikan.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.*;
import zw.co.reikan.loans.core.MsisdnUtil;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Jackson MUST bind through the no-args constructor + setters, hence the
 * {@code @JsonCreator} on it. Without that, Jackson 3 picks Lombok's all-args
 * constructor and passes {@code null} for every key the body omits — which
 * silently discarded the {@code type = NET_OF_FEES} default, so an application
 * sent without {@code type} was priced GROSS of fees: the customer received the
 * amount less the admin fee instead of the amount. Same trap as
 * {@code LoanDisbursementStatusResponse}. Pinned by
 * {@code LoanApplicationWebContractTest.omittedTypeDefaultsToNetOfFees}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@ToString
public class LoanRequest implements Serializable {

    @NotNull(message = "Loan amount is required")
    @Positive(message = "Loan amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "EC Number is required")
    private String ecnumber;

    @NotBlank(message = "Mobile number is required")
    @Pattern(regexp = MsisdnUtil.ZIMBABWE_MOBILE_REGEX, message = MsisdnUtil.ZIMBABWE_MOBILE_MESSAGE)
    private String mobileNumber;

    @NotNull(message = "Loan tenor is required")
    @Positive(message = "Loan tenor must be greater than zero")
    private Integer tenor;

    @NotBlank(message = "National ID is required")
    private String nationalId;

    @Builder.Default
    private LoanAmountType type = LoanAmountType.NET_OF_FEES;
    // --- Required for an APPLICATION only (LoanApplicationChecks), not a quote ---
    @NotBlank(groups = LoanApplicationChecks.class, message = "First name is required")
    private String fname;
    @NotBlank(groups = LoanApplicationChecks.class, message = "Last name is required")
    private String lname;

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;
    private Integer numberOfDependencies;
    private Integer numberOfChildren;
    @NotNull(groups = LoanApplicationChecks.class, message = "Marital status is required")
    private MaritalStatus maritalStatus;
    private String alternateContactNumber;
    @NotBlank(groups = LoanApplicationChecks.class, message = "Place of birth is required")
    private String placeOfBirth;
    private Title title;
    private String email;

    @JsonProperty("educationLevel")
    @JsonAlias("literacyLevel")
    private EducationLevel educationLevel;

    @Valid
    @NotNull(groups = LoanApplicationChecks.class, message = "Address is required")
    private Address address;
    @Valid
    @NotNull(groups = LoanApplicationChecks.class, message = "Employment detail is required")
    private EmploymentDetail employmentDetail;
    @Valid
    @NotNull(groups = LoanApplicationChecks.class, message = "Next of kin is required")
    private NextOfKin nextOfKin;
    private String nextOfKinIdNumber;
    private String nextOfKinName;
    private String nextOfKinPhone;
    private RelationshipType nextOfKinRelationShip;
    private String nextOfKinAddress;

    private Witness witness;
    @NotNull(groups = LoanApplicationChecks.class, message = "Purpose of loan is required")
    private LoanPurpose purposeOfLoan;
    /**
     * Sent to InnBucks as {@code businessLine}. There was no request field for
     * it, so the loan's lineOfBusiness could never be set and InnBucks never
     * received one. Takes the enum NAME ({@code SERVICES}); the description
     * ({@code "Services"}) is what goes on the wire to InnBucks.
     */
    @NotNull(groups = LoanApplicationChecks.class, message = "Line of business is required")
    private LineOfBusiness lineOfBusiness;
    private BigDecimal grossSalary;
    private BigDecimal netSalary;
    private String profession;
    @JsonProperty("merchant")
    @JsonAlias("loanFor")
    private String merchant;
    private Gender gender;
    private BankingDetail bankingDetail;
    private String productDescription;
    private String channelId;

    //Base64 images
    private String signatureData;
    private String nationalIdPicture;
    private String payslipPicture;

}
