package zw.co.innbucks.loans.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import zw.co.innbucks.loans.core.user.UserGroup;

import java.io.Serializable;
import java.util.List;

/** A signed-in session: the bearer token and who it belongs to. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LoginResponse implements Serializable {

    @ToString.Exclude
    @Schema(description = "Send as Authorization: Bearer <accessToken>")
    private String accessToken;

    @Schema(example = "Bearer")
    private String tokenType;

    @Schema(description = "Seconds until the token expires", example = "3600")
    private long expiresIn;

    @Schema(description = "True when the password is a temporary one the user must change before anything else",
            example = "false")
    private Boolean temporaryPassword;

    @Schema(example = "[\"CREDIT_MANAGER\"]")
    private List<UserGroup> groups;

    @Schema(example = "INNBUCKS")
    private String merchantCode;

    @Schema(example = "InnBucks MicroBank")
    private String merchantName;
}
