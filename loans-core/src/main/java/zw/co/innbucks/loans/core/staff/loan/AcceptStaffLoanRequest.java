package zw.co.innbucks.loans.core.staff.loan;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Accepting a loan (FR-SGL-027, FR-SGL-028): the quote's offer, amount and agreement, and a fresh middleware assertion
 * that the borrower has just entered their PIN or used biometrics.
 */
public record AcceptStaffLoanRequest(
        @NotNull(message = "offerId is required") Long offerId,
        @NotNull(message = "amount is required")
        @Positive(message = "amount must be more than 0")
        @Digits(integer = 17, fraction = 2, message = "amount has at most 2 decimals") BigDecimal amount,
        @NotNull(message = "agreementVersion is required") Integer agreementVersion,
        @NotBlank(message = "agreementSha256 is required")
        @Pattern(regexp = "[0-9a-f]{64}", message = "agreementSha256 is the quote's 64-character hex contentSha256")
        String agreementSha256,
        @NotBlank(message = "assertion is required")
        @Size(max = 8192, message = "assertion must be at most 8192 characters") String assertion) {

    @Override
    public String toString() {
        return "AcceptStaffLoanRequest[offerId=" + offerId + ", amount=" + amount + ", agreementVersion="
                + agreementVersion + "]";
    }
}
