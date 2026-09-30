package zw.co.innbucks.loans.core.authority;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** A credit authority level's name and limit, replaced whole (FR-PBL-028). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateCreditAuthorityLevelRequest implements CreditAuthorityLevelSettings {

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    @Schema(example = "Senior credit officer")
    private String name;

    @DecimalMin(value = "0.01", message = "Maximum principal must be more than zero")
    @Digits(integer = 17, fraction = 2, message = "Maximum principal must have at most 2 decimal places")
    @Schema(description = "The largest principal this level may approve; omit for any amount (one level at most)",
            example = "5000.00")
    private BigDecimal maximumPrincipal;
}
