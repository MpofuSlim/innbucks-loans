package zw.co.reikan.nanoloansweb;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DisbursementResponse {
    private DisbursementStatus status;
    private String message;
}
