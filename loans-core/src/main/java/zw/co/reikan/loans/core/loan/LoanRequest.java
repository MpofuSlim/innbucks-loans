package zw.co.reikan.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class LoanRequest implements Serializable {

    @NotNull(message = "Loan amount is required")
    @Positive(message = "Loan amount must be greater than zero")
    private BigDecimal amount;

    @NotBlank(message = "EC Number is required")
    private String ecnumber;

    @NotBlank(message = "Mobile number is required")
    private String mobileNumber;

    @NotNull(message = "Loan tenor is required")
    @Positive(message = "Loan tenor must be greater than zero")
    private Integer tenor;

    @NotBlank(message = "National ID is required")
    private String nationalId;

    private LoanAmountType type = LoanAmountType.NET_OF_FEES;
    private String fname;
    private String lname;

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;
    private Integer numberOfDependencies;
    private Integer numberOfChildren;
    private MaritalStatus maritalStatus;
    private String alternateContactNumber;
    private String placeOfBirth;
    private Title title;
    private String email;

    @JsonProperty("educationLevel")
    @JsonAlias("literacyLevel")
    private EducationLevel educationLevel;

    private Address address;
    private EmploymentDetail employmentDetail;
    private NextOfKin nextOfKin;
    private String nextOfKinIdNumber;
    private String nextOfKinName;
    private String nextOfKinPhone;
    private RelationshipType nextOfKinRelationShip;
    private String nextOfKinAddress;

    private Witness witness;
    private LoanPurpose purposeOfLoan;
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
