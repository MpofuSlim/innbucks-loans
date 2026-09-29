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
    private Responses responses = new Responses();

    /** How the deduction-response job reads back the answers to our lodgements. */
    @Data
    public static class Responses {
        /**
         * The furthest back, in days, one run reads responses. A run reads back to the oldest
         * lodgement still awaiting Ndasenda, so this bounds what one stuck loan can cost every run.
         */
        private int lookbackDays = 45;

        /** Days a lodgement may wait for Ndasenda's answer before it is reported overdue, once. */
        private int overdueAfterDays = 7;
    }
}
