package zw.co.innbucks.loans.core.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * The terms a quote is priced on. An application carries the same three fields and is priced
 * through them ({@link LoanApplicationRequest#quoteRequest()}), so a quote and the loan it leads to
 * cannot be priced differently.
 *
 * @param amountType how to read {@code amount}; NET_OF_FEES when omitted
 */
public record LoanQuoteRequest(
        @NotNull(message = "Loan amount is required")
        @Positive(message = "Loan amount must be greater than zero")
        @Schema(description = "What the customer receives (NET_OF_FEES) or borrows (GROSS_OF_FEES)", example = "500.00")
        BigDecimal amount,

        @Schema(description = "NET_OF_FEES when omitted", example = "NET_OF_FEES")
        LoanAmountType amountType,

        @NotNull(message = "Loan tenor is required")
        @Positive(message = "Loan tenor must be greater than zero")
        @Schema(description = "Months", example = "12")
        Integer tenor) {

    public LoanQuoteRequest {
        if (amountType == null) {
            amountType = LoanAmountType.NET_OF_FEES;
        }
    }
}
