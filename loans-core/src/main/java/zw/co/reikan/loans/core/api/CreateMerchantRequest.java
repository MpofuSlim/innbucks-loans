package zw.co.reikan.loans.core.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.loans.core.commission.CommissionStructure;
import zw.co.reikan.loans.core.loan.DisbursementType;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class CreateMerchantRequest {
    private String companyName;
    private String physicalAddress;
    private String contactPersonName;
    private String contactPersonMobileNumber;
    private String contactPersonEmail;
    private String accountNumber;
    private DisbursementType disbursementType;
    private String code;
    private CommissionStructure commissionStructure;;
    private Long commissionGroupId;
}
