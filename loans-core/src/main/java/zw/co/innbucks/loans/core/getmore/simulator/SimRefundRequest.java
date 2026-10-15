package zw.co.innbucks.loans.core.getmore.simulator;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * STAGING ONLY: a refund of a simulated sale by its order number, one credit memo that gives the amount back to the
 * voucher (assumption A3). Idempotent on {@code reference}.
 *
 * @param amount    everything still refundable on the sale when absent
 * @param reference {@code SIM-REFUND-<uuid>} when absent
 */
public record SimRefundRequest(
        @NotBlank(message = "orderNumber is required")
        @Size(max = 32, message = "orderNumber must be at most 32 characters") String orderNumber,
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 8, fraction = 2, message = "amount must have at most 2 decimal places and 8 digits before"
                + " them") BigDecimal amount,
        @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "reference must be 1 to 64 of A-Z, a-z, 0-9, '.', '_'"
                + " and '-'") String reference) {
}
