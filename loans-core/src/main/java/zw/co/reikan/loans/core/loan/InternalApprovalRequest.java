package zw.co.reikan.loans.core.loan;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

@Data
public class InternalApprovalRequest {
    private String comment;
    @NotNull(message = "Approval status is required")
    private InternalApprovalStatus status;
}
