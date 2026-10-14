package zw.co.innbucks.loans.core.getmore;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import zw.co.innbucks.loans.core.getmore.live.LiveAmountModel;

import java.time.Duration;

/**
 * GetMore Groceries coupons as Staff Grocery Loan vouchers ({@code loans.getmore.*}, env {@code GETMORE_*}).
 * {@link #mode} picks the {@link GetMoreStore}: OFF (the default, nothing calls GetMore), LIVE (GetMore's Magento 2.4
 * over OAuth 1.0a) or SIMULATED (staging only).
 *
 * <p>The four OAuth values and the simulator's API key are excluded from {@code toString}, and carry no Bean Validation
 * constraint, so a failed boot never prints them.
 */
@Data
@ConfigurationProperties(prefix = "loans.getmore")
public class GetMoreProperties {

    private GetMoreMode mode = GetMoreMode.OFF;
    /** The cart price rule every coupon is generated under ({@code GETMORE_RULE_ID}). */
    private long ruleId = 43;
    /** {@code [A-Z0-9]{2,8}-}; differs per environment sharing a store ({@code GETMORE_COUPON_PREFIX}). */
    private String couponPrefix = "INN-";
    /** The store's random characters after the prefix and nonce ({@code GETMORE_CODE_LENGTH}). */
    private int codeLength = 16;
    /** Items per page of every search, 1..300 ({@code GETMORE_PAGE_SIZE}). */
    private int pageSize = 50;
    /** Adoption floor: a coupon created before the first claim minus this was not made by loans. */
    private Duration clockSkew = Duration.ofMinutes(2);
    /** The pod's node (the Deployment's {@code NODE_NAME} fieldRef), never set by hand. */
    private String nodeName;

    private Issuance issuance = new Issuance();
    private Sync sync = new Sync();
    private Live live = new Live();
    private Simulator simulator = new Simulator();

    @Data
    public static class Issuance {
        private Duration retryAfter = Duration.ofSeconds(30);
        private Duration staleClaim = Duration.ofMinutes(5);
        private Duration inlineBudget = Duration.ofSeconds(10);
    }

    @Data
    public static class Sync {
        private Duration minInterval = Duration.ofMinutes(1);
        private Duration afterExpiry = Duration.ofDays(30);
        private Duration lockWait = Duration.ofSeconds(30);
    }

    /** GetMore's Magento store. Every value below the URLs is a credential. */
    @Data
    public static class Live {
        /** e.g. {@code https://qa.getmore.syncrony.com/rest/V1} ({@code GETMORE_BASE_URL}). */
        private String baseUrl;
        /**
         * Signs as this base instead of {@link #baseUrl} ({@code GETMORE_SIGNATURE_BASE_URL}): set to
         * {@code http://...} when GetMore terminates TLS in front of Magento without passing the scheme through.
         */
        private String signatureBaseUrl;
        @ToString.Exclude
        private String consumerKey;
        @ToString.Exclude
        private String consumerSecret;
        @ToString.Exclude
        private String accessToken;
        @ToString.Exclude
        private String accessTokenSecret;
        private LiveAmountModel amountModel = LiveAmountModel.NONE;
        private String currency = "USD";
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 10000;
    }

    /** STAGING ONLY: the in-process stand-in store. */
    @Data
    public static class Simulator {
        @ToString.Exclude
        private String apiKey;
        private String allowedNode;
        private String couponPrefix = "SIM-";
        private String currency = "USD";
        private boolean partialSpending = true;
        private boolean enforceCouponExpiry = true;
    }
}
