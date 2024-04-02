package zw.co.reikan.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoanDto implements Serializable {
    private LocalDateTime createdDate;
    private Long id;
    private BigDecimal principal;
    private BigDecimal disbursedAmount;
    private BigDecimal interestRate;
    private BigDecimal interestAmount;
    private BigDecimal feeRate;
    private BigDecimal monthlyInstallment;
    private int tenor;
    private LocalDate loanStartDate;
    private LocalDate loanEndDate;
    private String mobileNumber;
    private String ecNumber;
    private String firstName;
    private String lastName;
    private String nationalIdNumber;
    private String signature;
    private LoanApprovalStatus loanApprovalStatus;
    private String loanStatusMessage;
    private LocalDateTime dateApproved;
    private LoanDisbursementStatus disbursementStatus;
    private String disbursementStatusMessage;
    private LocalDateTime dateDisbursed;
    private String disbursementReference;
    private String approvalReference;
    private BigDecimal commissionRate;
    private BigDecimal grossedMonthlyDeduction;
    private LocalDate repaymentStartDate;
    private LocalDate repaymentEndDate;
    private BigDecimal agentCommissionRate;
    private BigDecimal agentCommission;
    private int numberOfDependencies;
    private MaritalStatus maritalStatus;
    private String alternateContactNumber;
    private String placeOfBirth;
    private Title title;
    private String email;
    private EducationLevel educationLevel;
    private Address address;
    private EmploymentDetail employmentDetail;
    private NextOfKin nextOfKin;
    private Witness witness;
    private LineOfBusiness lineOfBusiness;
    private LoanPurpose loanPurpose;
    private String profession;
    private Merchant merchant;
}
