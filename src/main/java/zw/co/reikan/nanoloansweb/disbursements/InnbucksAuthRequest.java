package zw.co.reikan.nanoloansweb.disbursements;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InnbucksAuthRequest {
    @JsonProperty("username")
    private String username;
    @JsonProperty("password")
    private String password;
}
