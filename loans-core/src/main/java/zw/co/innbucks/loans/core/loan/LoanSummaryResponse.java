package zw.co.innbucks.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import zw.co.innbucks.loans.core.disbursements.LoanAccountStatus;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A loan as a row in a list: who, how much, and where each stage stands. No documents and no
 * KYC beyond the name and EC number, so a page of loans stays small; GET /loans/{loanId} has the rest.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoanSummaryResponse {

    private Long id;
    private String reference;
    private String publicReference;
    private LocalDateTime createdAt;
    private String createdBy;
    private String merchantCode;
    private String merchantName;
    private String firstName;
    private String lastName;
    private String ecNumber;
    private String mobileNumber;
    private BigDecimal principal;
    private BigDecimal disbursedAmount;
    private int tenor;
    private BigDecimal monthlyInstallment;
    private LoanApprovalStatus ssbApprovalStatus;
    private InternalApprovalStatus creditApprovalStatus;
    private LoanAccountStatus bookingStatus;
    private LoanDisbursementStatus disbursementStatus;
}
