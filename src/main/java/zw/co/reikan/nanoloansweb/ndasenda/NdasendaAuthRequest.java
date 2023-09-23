package zw.co.reikan.nanoloansweb.ndasenda;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NdasendaAuthRequest {
    @JsonProperty("client_id")
    private String clientId;

    @JsonProperty("grant_type")
    private String grantType;

    @JsonProperty("password")
    private String password;

    @JsonProperty("username")
    private String username;
}
