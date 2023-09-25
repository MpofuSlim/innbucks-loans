package zw.co.reikan.nanoloansweb.ndasenda;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ndasenda")
public class NdasendaParameters {
    private String authEndpoint;
    private String deductionsRequestsEndpoint;
    private String findBatchEndpoint;
    private String commitDeductionsEndpoint;
    private String clientId;
    private String grantType;
    private String username;
    private String password;
    private String deductionCode;
    private String securityCode;
}
