package zw.co.innbucks.loans.core.getmore.simulator;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * STAGING ONLY: a sale at the simulated store's till, spending part of a voucher (assumption A2), as a GetMore outlet
 * would. Name the voucher by its loan or by its code, never both: the code goes in this body, never a URL. With a
 * {@code reference} it is idempotent: the same voucher and amount again answers the first sale.
 *
 * @param currency    the simulated store's when absent
 * @param basketTotal what the basket came to, at least {@code amount}; {@code amount} when absent
 * @param outletName  {@code outletId} when absent
 * @param reference   the till's own reference; {@code SIM-SALE-<uuid>} when absent
 * @param invoiced    false leaves the sale held (ordered, not yet invoiced, so not yet spent for loans); true when
 *                    absent
 */
public record SimSaleRequest(
        @Size(max = 64, message = "loanReference must be at most 64 characters") String loanReference,
        @Size(max = 64, message = "code must be at most 64 characters") String code,
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 8, fraction = 2, message = "amount must have at most 2 decimal places and 8 digits before"
                + " them") BigDecimal amount,
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO code, e.g. USD") String currency,
        @DecimalMin(value = "0.01", message = "basketTotal must be at least 0.01")
        @Digits(integer = 8, fraction = 2, message = "basketTotal must have at most 2 decimal places and 8 digits"
                + " before them") BigDecimal basketTotal,
        @NotBlank(message = "outletId is required")
        @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "outletId must be 1 to 64 of A-Z, a-z, 0-9, '.', '_'"
                + " and '-'") String outletId,
        @Size(max = 120, message = "outletName must be at most 120 characters") String outletName,
        @Pattern(regexp = "[A-Za-z0-9._-]{1,64}", message = "reference must be 1 to 64 of A-Z, a-z, 0-9, '.', '_'"
                + " and '-'") String reference,
        Boolean invoiced) {

    /** Exactly one of {@code loanReference} and {@code code}. */
    @JsonIgnore
    @AssertTrue(message = "give exactly one of loanReference and code")
    public boolean isLoanReferenceOrCode() {
        return SimTargets.exactlyOne(loanReference, code);
    }

    /** A basket is never worth less than what the voucher took off it. */
    @JsonIgnore
    @AssertTrue(message = "basketTotal must be at least amount")
    public boolean isBasketTotalAtLeastAmount() {
        return basketTotal == null || amount == null || basketTotal.compareTo(amount) >= 0;
    }

    /** Whether the sale is invoiced at once (the default). */
    public boolean invoicedNow() {
        return invoiced == null || invoiced;
    }

    @Override
    public String toString() {
        return "SimSaleRequest[loanReference=" + loanReference + ", code=" + (code == null ? null : "(given)")
                + ", amount=" + amount + ", currency=" + currency + ", basketTotal=" + basketTotal + ", outletId="
                + outletId + ", reference=" + reference + ", invoiced=" + invoiced + "]";
    }
}
