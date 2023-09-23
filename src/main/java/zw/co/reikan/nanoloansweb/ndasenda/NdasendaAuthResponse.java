package zw.co.reikan.nanoloansweb.ndasenda;

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
