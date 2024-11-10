package zw.co.reikan.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private String signatureData;
    private BigDecimal amount;
    private String ecnumber;
    private String mobileNumber;
    private int tenor;
    private String nationalId;
    private LoanAmountType type = LoanAmountType.NET_OF_FEES;
    private String fname;
    private String lname;
    private LocalDate dateOfBirth;
    private int numberOfDependencies;
    private int numberOfChildren;
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

}
