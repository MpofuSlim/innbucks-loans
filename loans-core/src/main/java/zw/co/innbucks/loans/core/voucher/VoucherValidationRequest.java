package zw.co.innbucks.loans.core.voucher;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** A till checking a voucher before the sale (FR-SGL-036). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "code")
public class VoucherValidationRequest {

    /** As keyed or scanned: digits, with or without spaces and dashes. */
    @NotBlank(message = "code is required")
    @Size(max = 64, message = "code must be at most 64 characters")
    private String code;

    @NotBlank(message = "outletId is required")
    @Size(max = 64, message = "outletId must be at most 64 characters")
    private String outletId;
}
