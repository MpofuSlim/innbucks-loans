package zw.co.innbucks.loans.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.loan.DisbursementType;

import java.io.Serializable;

/**
 * A merchant: the business an agent originates loans for, and where its loans are paid. The
 * account number is masked to its last four characters for everyone but SUPER_ADMIN.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MerchantResponse implements Serializable {
    private Long id;
    private String merchantCode;
    private String companyName;
    private DisbursementType disbursementType;
    private String accountNumber;
    private CommissionStructure commissionStructure;
    private CommissionGroupResponse commissionGroup;
    private String physicalAddress;
    private String contactPersonName;
    private String contactPersonMobileNumber;
    private String contactPersonEmail;
}
