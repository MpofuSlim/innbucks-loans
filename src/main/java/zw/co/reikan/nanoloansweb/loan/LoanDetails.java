package zw.co.reikan.nanoloansweb.loan;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class LoanDetails {
    private BigDecimal principal;
    private int tenor;
    private BigDecimal interestRate;
    private BigDecimal adminFee;
    private BigDecimal disbursedAmount;
    private BigDecimal installment;
    private BigDecimal interestAmount;
}
