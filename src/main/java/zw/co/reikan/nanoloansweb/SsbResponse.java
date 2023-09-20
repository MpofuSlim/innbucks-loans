package zw.co.reikan.nanoloansweb;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class SsbResponse {
    private SsbStatus status;
    private String message;
}
