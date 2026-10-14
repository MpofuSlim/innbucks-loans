package zw.co.innbucks.loans.core.staff.loan;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credit trying a loan's GetMore voucher again ({@code POST /staff-loans/{id}/voucher-issuance}), with why.
 * {@code generateAgain} is Credit stating that GetMore holds no usable coupon on the loan's prefix, so one more coupon
 * may be made: accepted only after a generate GetMore never showed (GENERATED_NOT_FOUND, PREFIX_NOT_HONOURED,
 * ORPHAN_LIVE). False when absent.
 */
public record StaffLoanVoucherIssuanceRequest(
        @NotBlank(message = "reason is required")
        @Size(max = 255, message = "reason must be at most 255 characters") String reason,
        Boolean generateAgain) {

    /** Whether one more generate is allowed: false unless asked for. */
    public boolean generateAgainAsked() {
        return Boolean.TRUE.equals(generateAgain);
    }
}
