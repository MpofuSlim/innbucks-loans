package zw.co.innbucks.loans.core.authority;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** A credit authority level to add (FR-PBL-028). Its code is fixed once created. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCreditAuthorityLevelRequest implements CreditAuthorityLevelSettings {

    @NotBlank(message = "Code is required")
    @Pattern(regexp = "[A-Z][A-Z0-9_]{2,39}",
            message = "Code must be 3 to 40 capital letters, digits or underscores, starting with a letter")
    @Schema(description = "The level's code, used when giving it to a user; fixed once created",
            example = "SENIOR_CREDIT_OFFICER")
    private String code;

    @NotBlank(message = "Name is required")
    @Size(max = 80, message = "Name must be at most 80 characters")
    @Schema(example = "Senior credit officer")
    private String name;

    @DecimalMin(value = "0.01", message = "Maximum principal must be more than zero")
    @Digits(integer = 17, fraction = 2, message = "Maximum principal must have at most 2 decimal places")
    @Schema(description = "The largest principal this level may approve; omit for any amount (one level at most)",
            example = "3000.00")
    private BigDecimal maximumPrincipal;
}
