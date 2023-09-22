package zw.co.reikan.nanoloansweb;

import lombok.Builder;
import lombok.Data;
import zw.co.reikan.nanoloansweb.loan.DisbursementStatus;

@Data
@Builder
public class DisbursementResponse {
    private DisbursementStatus status;
    private String reference;
    private String message;
}
