package zw.co.innbucks.loans.core;

import lombok.Builder;
import lombok.Data;
import zw.co.innbucks.loans.core.loan.DisbursementStatus;

@Data
@Builder
public class DisbursementResponse {
    private DisbursementStatus status;
    private String approvalCode;
    private String internalReference;
    private String message;
}
