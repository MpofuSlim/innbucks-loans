package zw.co.innbucks.loans.core.voucher;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/** A till spending a voucher (FR-SGL-036). Sending the same {@code reference} again returns the first answer. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "code")
public class VoucherRedemptionRequest {

    /** As keyed or scanned: digits, with or without spaces and dashes. */
    @NotBlank(message = "code is required")
    @Size(max = 64, message = "code must be at most 64 characters")
    private String code;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
    @Digits(integer = 17, fraction = 2, message = "amount must have at most 2 decimal places")
    private BigDecimal amount;

    /** The till's currency; refused when it is not the voucher's. */
    @NotBlank(message = "currency is required")
    @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO code, e.g. USD")
    private String currency;

    @NotBlank(message = "outletId is required")
    @Size(max = 64, message = "outletId must be at most 64 characters")
    private String outletId;

    @Size(max = 120, message = "outletName must be at most 120 characters")
    private String outletName;

    /** The merchant's own reference for this sale: unique among its sales, and what makes a retried redemption safe. */
    @NotBlank(message = "reference is required")
    @Size(max = 64, message = "reference must be at most 64 characters")
    private String reference;
}
