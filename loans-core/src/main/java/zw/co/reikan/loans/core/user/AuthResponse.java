package zw.co.reikan.loans.core.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class AuthResponse implements Serializable {
    @JsonProperty("access")
    private String accessToken;
    @JsonProperty("expires_in")
    private Integer expiresIn;
    @JsonProperty("refresh_expires_in")
    private Integer refreshExpiresIn;
    @JsonProperty("refresh")
    private String refreshToken;
    @JsonProperty("type")
    private String tokenType;
}
