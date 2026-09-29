package zw.co.innbucks.loans.core;

import lombok.Builder;
import lombok.Data;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;

@Data
@Builder
public class LoanResponse {
    private String message;
    private String internalReference;
    private LoanApprovalStatus loanApprovalStatus;
}
