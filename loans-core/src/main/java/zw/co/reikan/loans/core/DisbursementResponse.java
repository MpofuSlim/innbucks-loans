package zw.co.reikan.loans.core;

import lombok.Builder;
import lombok.Data;
import zw.co.reikan.loans.core.loan.DisbursementStatus;

@Data
@Builder
public class DisbursementResponse {
    private DisbursementStatus status;
    private String approvalCode;
    private String internalReference;
    private String message;
}
