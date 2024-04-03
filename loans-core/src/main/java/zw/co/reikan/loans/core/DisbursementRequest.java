package zw.co.reikan.loans.core;

import lombok.Builder;
import lombok.Data;
import zw.co.reikan.loans.core.loan.DisbursementType;

import java.math.BigDecimal;

@Data
@Builder
public class DisbursementRequest {
    private BigDecimal amount;
    private String mobileNumber;
    private String accountNumber;
    private String reference;
    private DisbursementType disbursementType;
}
