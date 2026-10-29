package zw.co.innbucks.loans.core.staff.loan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A person's record that the bank confirmed it never booked a cash payout: how they know. Kept as its hash. */
public record StaffLoanPayoutNotBookedRequest(
        @NotBlank(message = "note is required")
        @Size(max = 500, message = "note must be at most 500 characters") String note) {
}
