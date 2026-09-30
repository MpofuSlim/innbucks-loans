package zw.co.innbucks.loans.core.authority;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The credit authority level to give a user (FR-PBL-028), or none. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserCreditAuthorityRequest {

    @Size(max = 40, message = "Level must be at most 40 characters")
    @Schema(description = "A level's code (GET /credit-authority-levels); null or omitted to take the user's level"
            + " away", example = "SENIOR_CREDIT_OFFICER")
    private String level;
}
