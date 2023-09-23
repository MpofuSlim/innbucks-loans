package zw.co.reikan.nanoloansweb;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class DisbursementRequest {
    private BigDecimal amount;
    private String mobileNumber;
    private String reference;
}
