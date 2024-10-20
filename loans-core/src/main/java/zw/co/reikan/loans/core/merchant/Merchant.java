package zw.co.reikan.loans.core.merchant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import zw.co.reikan.loans.core.loan.BaseEntity;
import zw.co.reikan.loans.core.loan.DisbursementType;

import jakarta.persistence.*;

@Data
@Table(name = "merchant", indexes = {
        @Index(name = "idx_merchant_code", columnList = "merchantCode")
})
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
public class Merchant extends BaseEntity {
    public static final String DEFAULT_MERCHANT_CODE = "INNBUCKS";
    private String merchantCode;
    private String name;
    private String accountNumber;
    @Enumerated(EnumType.STRING)
    private DisbursementType disbursementType;
}
