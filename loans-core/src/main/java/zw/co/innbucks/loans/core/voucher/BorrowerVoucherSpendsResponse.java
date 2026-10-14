package zw.co.innbucks.loans.core.voucher;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Every spend and refund of a borrower's voucher, oldest first, with what was left after each, for the SuperApp. No
 * code. For a GetMore voucher the figures are as the last sync read them ({@code balanceAsOf}), and {@code held} is
 * ordered at GetMore but not yet invoiced.
 *
 * @param spent     what has been spent, net of refunds
 * @param balance   what can still be spent
 * @param simulated GetMore only, and only on staging's simulated store: true
 */
public record BorrowerVoucherSpendsResponse(String loanReference, BigDecimal faceValue, BigDecimal spent,
                                            BigDecimal held, BigDecimal balance, String currency,
                                            @JsonInclude(JsonInclude.Include.NON_NULL) LocalDateTime balanceAsOf,
                                            boolean partialSpending,
                                            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean simulated,
                                            List<Movement> movements) {

    public enum Kind { SPEND, REFUND }

    /**
     * One spend or refund. {@code at} is when it happened at the store; {@code orderNumber} is the store's own (absent
     * for a purchase at one of loans' tills).
     */
    public record Movement(Kind kind, BigDecimal amount, BigDecimal balanceAfter, LocalDateTime at, String outlet,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String orderNumber) {
    }
}
