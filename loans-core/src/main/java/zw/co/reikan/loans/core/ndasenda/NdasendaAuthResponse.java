package zw.co.reikan.loans.core.ndasenda;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class NdasendaAuthResponse {
    @JsonProperty("access_token")
    private String accessToken;
    @JsonProperty("refresh_token")
    private String refreshToken;
    @JsonProperty("expires_in")
    private int expiresIn;
}
