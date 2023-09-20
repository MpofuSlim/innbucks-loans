package zw.co.reikan.nanoloansweb;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Builder
public class SsbApprovalRequest implements Serializable {
    private BigDecimal amount;
    private String ecnumber;
}
