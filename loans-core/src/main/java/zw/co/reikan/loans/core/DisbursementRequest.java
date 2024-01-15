package zw.co.reikan.loans.core;

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
