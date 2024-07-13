package zw.co.reikan.loans.core.loan;

import lombok.Data;

@Data
public class InternalApprovalRequest {
    private String comment;
    private InternalApprovalStatus status;
}
