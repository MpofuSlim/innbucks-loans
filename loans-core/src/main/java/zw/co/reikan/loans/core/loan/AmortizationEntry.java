package zw.co.reikan.loans.core.loan;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class AmortizationEntry {
    private int paymentNumber;
    private BigDecimal regularMonthlyPayment;
    private BigDecimal principalPayment;
    private BigDecimal interestPayment;
    private BigDecimal remainingPrincipal;
    private BigDecimal grossedMonthlyPayment;
}