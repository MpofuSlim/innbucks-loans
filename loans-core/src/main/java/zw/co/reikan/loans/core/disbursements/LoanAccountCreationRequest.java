package zw.co.reikan.loans.core.disbursements;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoanAccountCreationRequest {

    @JsonProperty("firstName")
    private String firstName;

    @JsonProperty("lastName")
    private String lastName;

    @JsonProperty("idNumber")
    private String idNumber;

    @JsonProperty("address")
    private String address;

    @JsonProperty("dateOfBirth")
    private String dateOfBirth;

    @JsonProperty("amount")
    private Integer amount;
    @JsonProperty("currency")
    private String currency;
    @JsonProperty("product")
    private String product;
    @JsonProperty("tenureInMonths")
    private Integer tenureInMonths;
    @JsonProperty("employer")
    private String employer;
    @JsonProperty("maritalStatus")
    private String maritalStatus;
    @JsonProperty("grossSalary")
    private Integer grossSalary;
    @JsonProperty("numberOfDependents")
    private Integer numberOfDependents;
    @JsonProperty("numberOfChildren")
    private Integer numberOfChildren;
    @JsonProperty("msisdn")
    private String msisdn;

    @JsonProperty("businessLine")
    private String businessLine;

    @JsonProperty("loanPurpose")
    private String loanPurpose;

    @JsonProperty("placeOfBirth")
    private String placeOfBirth;

    @JsonProperty("employerNumber")
    private String employerNumber;

    @JsonProperty("repaymentFrequency")
    private String repaymentFrequency;

    @JsonProperty("nextOfKinIdNumber")
    private String nextOfKinIdNumber;

    @JsonProperty("nextOfKinFullName")
    private String nextOfKinFullName;

    @JsonProperty("nextOfKinMsisdn")
    private String nextOfKinMsisdn;

    @JsonProperty("nextOfKinAddress")
    private String nextOfKinAddress;

    @JsonProperty("nextOfKinRelationship")
    private String nextOfKinRelationship;

    @JsonProperty("employmentStartDate")
    private String employmentStartDate;

    @JsonProperty("participantReference")
    private String participantReference;

}
