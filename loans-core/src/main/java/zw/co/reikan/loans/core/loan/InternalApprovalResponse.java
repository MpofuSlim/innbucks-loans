package zw.co.reikan.loans.core.loan;

import lombok.Data;

@Data
public class InternalApprovalResponse {
    private String message;
    private LoanDto loan;

    public InternalApprovalResponse(String message) {
        this.message = message;
    }
}
