package zw.co.reikan.loans.core.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.loans.core.loan.DisbursementType;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class CreateMerchantRequest {
    private String name;
    private String accountNumber;
    private DisbursementType disbursementType;
    private String code;
}
