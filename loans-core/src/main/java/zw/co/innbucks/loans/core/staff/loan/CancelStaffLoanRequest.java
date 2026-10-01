package zw.co.innbucks.loans.core.staff.loan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a loan is stopped before it is paid out. */
public record CancelStaffLoanRequest(
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters") String reason) {
}
