package zw.co.innbucks.loans.core.ndasenda;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

@Data
public class NdasendaAuthResponse {
    @ToString.Exclude
    @JsonProperty("access_token")
    private String accessToken;
    @ToString.Exclude
    @JsonProperty("refresh_token")
    private String refreshToken;
    @JsonProperty("expires_in")
    private int expiresIn;
}
