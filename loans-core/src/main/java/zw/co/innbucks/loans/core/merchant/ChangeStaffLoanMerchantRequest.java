package zw.co.innbucks.loans.core.merchant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** @param merchantCode the merchant the Staff Grocery Loan is to be for from now on */
public record ChangeStaffLoanMerchantRequest(
        @NotBlank(message = "merchantCode is required")
        @Size(max = 255, message = "merchantCode must be at most 255 characters") String merchantCode) {
}
