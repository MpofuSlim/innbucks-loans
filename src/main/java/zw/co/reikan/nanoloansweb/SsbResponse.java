package zw.co.reikan.nanoloansweb;

import lombok.Builder;
import lombok.Data;
import zw.co.reikan.nanoloansweb.loan.SsbStatus;

@Builder
@Data
public class SsbResponse {
    private SsbStatus status;
    private String message;
}
