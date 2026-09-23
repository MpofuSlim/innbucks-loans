package zw.co.reikan.loans.core.disbursements;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The InnBucks loan-inquiry response.
 *
 * <p>Jackson MUST bind through the no-args constructor + setters, hence the
 * {@code @JsonCreator} on it. Without that, Jackson 3 picks Lombok's all-args
 * constructor, passes {@code null} for {@link #success} — which we derive from
 * the HTTP status and which never appears on the wire — and fails on the
 * primitive, so every real inquiry came back as "Unexpected error" and the loan
 * sat PENDING forever. Pinned by {@code InnbucksLoanApiContractTest}.
 */
@Data
@Builder
@NoArgsConstructor(onConstructor_ = @JsonCreator)
@AllArgsConstructor
public class LoanDisbursementStatusResponse {
    private String responseCode;
    private String responseDescription;
    private String reference;
    private String participantReference;
    private LoanDisbursementStatus status;
    private String statusMessage;
    private BigDecimal amount;
    private LocalDateTime disbursementDate;
    private boolean success;
    
    // Helper method to determine if the loan was found
    public boolean isLoanFound() {
        return !"004".equals(responseCode);
    }
    
    // Helper method to determine if the loan was approved/completed successfully
    public boolean isApproved() {
        return "000".equals(responseCode);
    }
    
    // Helper method to determine the loan status from the response
    public LoanDisbursementStatus determineLoanStatus() {
        if (!isLoanFound()) {
            return LoanDisbursementStatus.FAILED;
        }
        
        if (isApproved()) {
            if (additionalData != null && additionalData.getLoanDetails() != null) {
                String status = additionalData.getLoanDetails().getStatus();
                if ("SETTLED".equals(status)) {
                    return LoanDisbursementStatus.SUCCESS;
                }
            }
        }
        
        return LoanDisbursementStatus.PENDING;
    }
    
    private AdditionalData additionalData;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdditionalData {
        private LoanDetails loanDetails;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoanDetails {
        private String id;
        private String created;
        private String updated;
        private String firstName;
        private String lastName;
        private String idNumber;
        private String dateOfBirth;
        private String address;
        private String msisdn;
        private Double amount;
        private String status;
        private String product;
        private String type;
        private Double meanRate;
        private String batchId;
        private String serialId;
        private String currency;
        private String clientId;
        private String reference;
        private Double deduction;
        private String loanSeries;
        private Boolean preApproved;
        private Double interestRate;
        private String accountNumber;
        private String applicationId;
        private String placeOfBirth;
        private String maritalStatus;
        private Integer numberOfDependents;
        private String nextOfKinIdNumber;
        private String nextOfKinFullName;
        private String nextOfKinMsisdn;
        private String nextOfKinAddress;
        private String nextOfKinRelationship;
        private String employer;
        private String businessLine;
        private String loanPurpose;
        private String employerNumber;
        private String employmentStartDate;
        private Integer tenureInMonths;
        private Double grossSalary;
        private String accountBranchId;
        private String loanAccountNumber;
        private Double disbursementAmount;
        private String repaymentFrequency;
        private String participantReference;
        private Double netDisbursementAmount;
        private String settlementAccount;
    }
}