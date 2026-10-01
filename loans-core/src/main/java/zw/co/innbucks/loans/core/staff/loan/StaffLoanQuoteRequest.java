package zw.co.innbucks.loans.core.staff.loan;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** The offer and the amount the borrower chose (FR-SGL-012). */
public record StaffLoanQuoteRequest(
        @NotNull(message = "offerId is required") Long offerId,
        @NotNull(message = "amount is required")
        @Positive(message = "amount must be more than 0")
        @Digits(integer = 17, fraction = 2, message = "amount has at most 2 decimals") BigDecimal amount) {
}
