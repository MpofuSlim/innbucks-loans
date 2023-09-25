package zw.co.reikan.nanoloansweb.ndasenda;

import lombok.Builder;
import lombok.Data;
import zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus;

@Builder
@Data
public class LoanApprovalResponse {
    private LoanApprovalStatus status;
    private String message;
    private String reference;
    private String batchNumber;
}
