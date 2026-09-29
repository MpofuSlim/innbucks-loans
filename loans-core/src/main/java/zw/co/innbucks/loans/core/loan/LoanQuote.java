package zw.co.innbucks.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** What a loan on the requested terms would cost: the amounts, the rates and the repayment schedule. */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoanQuote {
    private BigDecimal principal;
    private int tenor;
    /** Monthly, percent. */
    private BigDecimal interestRate;
    private BigDecimal interestAmount;
    /** The admin fee, percent of principal. */
    private BigDecimal feeRate;
    private BigDecimal feeAmount;
    /** What the customer receives: principal less the admin fee. */
    private BigDecimal disbursedAmount;
    private BigDecimal monthlyInstallment;
    /** Percent of principal. */
    private BigDecimal commissionRate;
    /** The monthly installment grossed up by the commission rate: what SSB is asked to deduct. */
    private BigDecimal grossedMonthlyDeduction;
    private LocalDate startDate;
    private BigDecimal agentCommission;
    private BigDecimal providerCommission;
    private BigDecimal agentCommissionRate;
    private BigDecimal providerCommissionRate;
    /** Whether the two commission rates are percentages of the total commission rather than amounts. */
    private boolean commissionPercentage;
    private List<AmortizationEntry> amortizationSchedule;

    public LoanQuote add(AmortizationEntry entry) {
        ensureSchedule().add(entry);
        this.interestAmount = ensureInterestAmount().add(entry.getInterestPayment());
        return this;
    }

    private List<AmortizationEntry> ensureSchedule() {
        if (CollectionUtils.isEmpty(amortizationSchedule)) {
            amortizationSchedule = new ArrayList<>();
        }
        return amortizationSchedule;
    }

    private BigDecimal ensureInterestAmount() {
        if (interestAmount == null) {
            interestAmount = BigDecimal.ZERO;
        }
        return interestAmount;
    }

}
