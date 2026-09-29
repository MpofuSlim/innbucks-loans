package zw.co.reikan.loans.core.ndasenda;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ndasenda")
public class NdasendaParameters {
    private String authEndpoint;
    private String deductionRequestsEndpoint;
    private String deductionResponsesByDateRangeEndpoint;
    private String deductionResponsesByBatchId;
    private String findBatchEndpoint;
    private String deductionRequestsByDateRangeEndpoint;
    private String commitDeductionsEndpoint;
    private String clientId;
    private String grantType;
    private String username;
    @ToString.Exclude
    private String password;
    private String deductionCode;
    @ToString.Exclude
    private String securityCode;
}
