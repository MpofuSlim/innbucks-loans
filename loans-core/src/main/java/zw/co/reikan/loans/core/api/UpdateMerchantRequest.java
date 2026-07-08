package zw.co.reikan.loans.core.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.loans.core.loan.DisbursementType;

/**
 * Editable merchant fields. Deliberately omits {@code code},
 * {@code commissionStructure} and {@code commissionGroupId} — those are fixed at
 * creation and cannot be changed via update.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMerchantRequest {
    private String companyName;
    private String physicalAddress;
    private String contactPersonName;
    private String contactPersonMobileNumber;
    private String contactPersonEmail;
    private String accountNumber;
    private DisbursementType disbursementType;
}
