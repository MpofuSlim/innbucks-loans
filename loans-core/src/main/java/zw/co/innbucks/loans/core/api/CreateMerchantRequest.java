package zw.co.innbucks.loans.core.api;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.loan.DisbursementType;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class CreateMerchantRequest {
    @NotBlank(message = "Company name is required")
    private String companyName;
    private String physicalAddress;
    private String contactPersonName;
    private String contactPersonMobileNumber;
    private String contactPersonEmail;
    private String accountNumber;
    @NotNull(message = "Disbursement type is required")
    private DisbursementType disbursementType;
    @NotBlank(message = "Merchant code is required")
    private String merchantCode;
    private CommissionStructure commissionStructure;
    private Long commissionGroupId;
}
