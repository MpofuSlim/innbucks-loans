package zw.co.innbucks.loans.core.borrower;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Staging only: the phone and sign-in methods a test assertion is to claim, as the middleware would. Bound through the
 * no-argument constructor and setters, so a body that leaves {@code methods} out keeps its default.
 */
@Data
@NoArgsConstructor
public class TestAssertionRequest {

    /** The SuperApp user's phone, as the middleware writes it ({@code +263772123123}, {@code 0772123123}, ...). */
    @NotBlank(message = "phone is required")
    @Size(max = 20, message = "phone must be at most 20 characters")
    private String phone;

    /**
     * How they authenticated, the middleware's {@code amr}: {@code pin}, {@code fpt} (fingerprint) or {@code face}.
     * Defaults to {@code pin}; send {@code ["otp"]} to see an approval refused as not a fresh PIN.
     */
    @Size(max = 5, message = "methods must name at most 5 methods")
    @NotNull(message = "methods must be a list")
    private List<@NotNull(message = "a method cannot be empty")
            @Pattern(regexp = "[a-z]{1,16}", message = "a method is 1 to 16 lowercase letters") String> methods =
            new ArrayList<>(List.of("pin"));
}
