package zw.co.innbucks.loans.core.voucher;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Why a voucher's code is being shown in full, or why it is being cancelled: kept on the record. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherReasonRequest {

    @NotBlank(message = "reason is required")
    @Size(max = 255, message = "reason must be at most 255 characters")
    private String reason;
}
