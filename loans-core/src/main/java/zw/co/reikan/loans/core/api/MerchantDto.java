package zw.co.reikan.loans.core.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import zw.co.reikan.loans.core.loan.DisbursementType;

import java.io.Serializable;

@Data
@Builder
@AllArgsConstructor
@RequiredArgsConstructor
public class MerchantDto implements Serializable {
    private Long id;
    private String merchantCode;
    private String name;
    private String accountNumber;
    private DisbursementType disbursementType;
}
