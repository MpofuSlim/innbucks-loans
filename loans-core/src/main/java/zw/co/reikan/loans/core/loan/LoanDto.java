package zw.co.reikan.loans.core.loan;

import lombok.Builder;
import lombok.Data;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
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
}
