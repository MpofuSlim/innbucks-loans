package zw.co.reikan.nanoloansweb.loan;

import lombok.Builder;
import lombok.Data;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class LoanDetails {
    List<AmortizationEntry> amortization;
    private BigDecimal principal;
    private int tenor;
    private BigDecimal interestRate;
    private BigDecimal adminFeeAmount;
    private BigDecimal adminFeeRate;
    private BigDecimal disbursedAmount;
    private BigDecimal regularMonthlyInstallment;
    private BigDecimal interestAmount;
    private BigDecimal commissionRate;
    private BigDecimal grossedMonthlyInstallment;
    private LocalDate startDate;


    public LoanDetails add(AmortizationEntry entry) {
        ensureAmortizations().add(entry);
        this.interestAmount = ensureInterestAmount().add(entry.getInterestPayment());
        return this;
    }

    private List<AmortizationEntry> ensureAmortizations() {
        if (CollectionUtils.isEmpty(amortization)) {
            amortization = new ArrayList<>();
        }
        return amortization;
    }

    private BigDecimal ensureInterestAmount() {
        if (interestAmount == null) {
            interestAmount = BigDecimal.ZERO;
        }
        return interestAmount;
    }

}
