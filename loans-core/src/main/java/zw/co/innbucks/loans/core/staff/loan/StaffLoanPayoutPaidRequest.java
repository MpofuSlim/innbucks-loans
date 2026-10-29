package zw.co.innbucks.loans.core.staff.loan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A person's record that the bank paid a cash payout it took or may have taken: the bank's own reference for the
 * payment, and how they know (who at the bank said so, and when). The note is kept as its hash.
 */
public record StaffLoanPayoutPaidRequest(
        @NotBlank(message = "bankReference is required")
        @Size(max = 64, message = "bankReference must be at most 64 characters") String bankReference,
        @NotBlank(message = "note is required")
        @Size(max = 500, message = "note must be at most 500 characters") String note) {
}
