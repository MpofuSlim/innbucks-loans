package zw.co.reikan.loans.core;

import lombok.Builder;
import lombok.Data;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;

@Data
@Builder
public class LoanResponse {
    private String message;
    private String internalReference;
    private LoanApprovalStatus loanApprovalStatus;
}
