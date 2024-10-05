package zw.co.reikan.loans.core.api;

import lombok.Data;
import zw.co.reikan.loans.core.loan.DisbursementType;

@Data
public class CreateMerchantRequest {
    private String name;
    private String accountNumber;
    private DisbursementType disbursementType;
}
