package zw.co.innbucks.loans.core.disbursements;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.ToString;

@Data
@Builder
public class InnbucksAuthRequest {
    @JsonProperty("username")
    private String username;
    @ToString.Exclude
    @JsonProperty("password")
    private String password;
}
