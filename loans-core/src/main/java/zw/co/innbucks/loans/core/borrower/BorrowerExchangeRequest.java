package zw.co.innbucks.loans.core.borrower;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** The middleware's assertion that the SuperApp user has just signed in, traded for a borrower session. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "assertion")
public class BorrowerExchangeRequest {

    /** The compact signed JWT, exactly as the middleware returned it. */
    @NotBlank(message = "assertion is required")
    @Size(max = 8192, message = "assertion must be at most 8192 characters")
    private String assertion;
}
