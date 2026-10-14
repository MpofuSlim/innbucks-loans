package zw.co.innbucks.loans.core.getmore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import zw.co.innbucks.loans.core.getmore.live.LiveAmountModel;

import java.time.Duration;

/**
 * GetMore Groceries coupons as Staff Grocery Loan vouchers ({@code loans.getmore.*}, env {@code GETMORE_*}).
 * {@link #mode} picks the {@link GetMoreStore}: OFF (the default, nothing calls GetMore), LIVE (GetMore's Magento 2.4
 * over OAuth 1.0a) or SIMULATED (staging only).
 *
 * <p>The four OAuth values and the simulator's API key are excluded from {@code toString}, and carry no Bean Validation
 * constraint, so a failed boot never prints them. What they must be is checked, by name only, in
 * {@link GetMoreModeReport}; the rules below are about shape, and each message names its property.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "loans.getmore")
public class GetMoreProperties {

    /** The nonce GetMoreCodes draws (8 characters) and the dash after it. */
    static final int NONCE_AND_DASH = 9;
    /**
     * The longest code that still fits one SMS: at 39 characters, with a balance of up to 9 999.99, the voucher
     * message's named-merchant form (or the simulator's test sentence) is exactly 160 characters.
     */
    static final int MAX_CODE_CHARACTERS = 39;
    /** What a stale claim must outlast: eight calls at their timeouts, each with two seconds' slack. */
    static final int CALLS_PER_ATTEMPT = 8;
    static final long SLACK_PER_CALL_MS = 2000;
    private static final String PREFIX_SHAPE = "^[A-Z0-9]{2,8}-$";

    @NotNull(message = "loans.getmore.mode must be OFF, LIVE or SIMULATED (GETMORE_MODE)")
    private GetMoreMode mode = GetMoreMode.OFF;
    /** The cart price rule every coupon is generated under ({@code GETMORE_RULE_ID}). */
    @Min(value = 1, message = "loans.getmore.rule-id must be a sales rule id above 0 (GETMORE_RULE_ID)")
    private long ruleId = 43;
    /** {@code [A-Z0-9]{2,8}-}; differs per environment sharing a store ({@code GETMORE_COUPON_PREFIX}). */
    @NotNull(message = "loans.getmore.coupon-prefix must be set (GETMORE_COUPON_PREFIX)")
    @Pattern(regexp = PREFIX_SHAPE,
            message = "loans.getmore.coupon-prefix must be 2 to 8 of A-Z and 0-9, then a dash, e.g. INN-"
                    + " (GETMORE_COUPON_PREFIX)")
    private String couponPrefix = "INN-";
    /** The store's random characters after the prefix and nonce ({@code GETMORE_CODE_LENGTH}). */
    @Min(value = 8, message = "loans.getmore.code-length must be at least 8 (GETMORE_CODE_LENGTH)")
    @Max(value = 32, message = "loans.getmore.code-length must be at most 32 (GETMORE_CODE_LENGTH)")
    private int codeLength = 16;
    /** Items per page of every search, 1..300 ({@code GETMORE_PAGE_SIZE}). */
    @Min(value = 1, message = "loans.getmore.page-size must be at least 1 (GETMORE_PAGE_SIZE)")
    @Max(value = 300, message = "loans.getmore.page-size must be at most 300, Magento's limit (GETMORE_PAGE_SIZE)")
    private int pageSize = 50;
    /** Adoption floor: a coupon created before the first claim minus this was not made by loans. */
    private Duration clockSkew = Duration.ofMinutes(2);
    /** The pod's node (the Deployment's {@code NODE_NAME} fieldRef), never set by hand. */
    private String nodeName;

    @Valid
    private Issuance issuance = new Issuance();
    @Valid
    private Sync sync = new Sync();
    @Valid
    private Live live = new Live();
    @Valid
    private Simulator simulator = new Simulator();

    /** The configured prefix a code is issued under in {@code mode}: the simulator's own when SIMULATED. */
    public String couponPrefixFor(GetMoreMode storeMode) {
        return storeMode == GetMoreMode.SIMULATED ? simulator.getCouponPrefix() : couponPrefix;
    }

    @AssertTrue(message = "loans.getmore.coupon-prefix and loans.getmore.simulator.coupon-prefix must differ"
            + " (GETMORE_COUPON_PREFIX, GETMORE_SIMULATOR_COUPON_PREFIX): a simulated code must never read as a real"
            + " one")
    public boolean isPrefixesDistinct() {
        return couponPrefix == null || simulator == null || simulator.getCouponPrefix() == null
                || !couponPrefix.equalsIgnoreCase(simulator.getCouponPrefix());
    }

    @AssertTrue(message = "a GetMore code must fit one SMS: the coupon prefix's length + 9 (the nonce and its dash) +"
            + " loans.getmore.code-length must be at most 39, for loans.getmore.coupon-prefix and"
            + " loans.getmore.simulator.coupon-prefix alike (GETMORE_CODE_LENGTH)")
    public boolean isCodeFitsOneMessage() {
        return fits(couponPrefix) && (simulator == null || fits(simulator.getCouponPrefix()));
    }

    @AssertTrue(message = "loans.getmore.issuance.stale-claim must be at least 8 x (connect-timeout-ms +"
            + " read-timeout-ms + 2000 ms), so a claim is never taken over while its calls can still be running"
            + " (GETMORE_ISSUANCE_STALE_CLAIM)")
    public boolean isStaleClaimLongEnough() {
        if (issuance == null || issuance.getStaleClaim() == null || live == null) {
            return true;
        }
        long perCall = (long) live.getConnectTimeoutMs() + live.getReadTimeoutMs() + SLACK_PER_CALL_MS;
        return issuance.getStaleClaim().toMillis() >= CALLS_PER_ATTEMPT * perCall;
    }

    private boolean fits(String prefix) {
        return prefix == null || prefix.length() + NONCE_AND_DASH + codeLength <= MAX_CODE_CHARACTERS;
    }

    @Data
    public static class Issuance {
        @NotNull(message = "loans.getmore.issuance.retry-after must be set (GETMORE_ISSUANCE_RETRY_AFTER)")
        private Duration retryAfter = Duration.ofSeconds(30);
        @NotNull(message = "loans.getmore.issuance.stale-claim must be set (GETMORE_ISSUANCE_STALE_CLAIM)")
        private Duration staleClaim = Duration.ofMinutes(5);
        @NotNull(message = "loans.getmore.issuance.inline-budget must be set (GETMORE_ISSUANCE_INLINE_BUDGET)")
        private Duration inlineBudget = Duration.ofSeconds(10);
    }

    @Data
    public static class Sync {
        @NotNull(message = "loans.getmore.sync.min-interval must be set (GETMORE_SYNC_MIN_INTERVAL)")
        private Duration minInterval = Duration.ofMinutes(1);
        @NotNull(message = "loans.getmore.sync.after-expiry must be set (GETMORE_SYNC_AFTER_EXPIRY)")
        private Duration afterExpiry = Duration.ofDays(30);
        @NotNull(message = "loans.getmore.sync.lock-wait must be set (GETMORE_SYNC_LOCK_WAIT)")
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
        @NotNull(message = "loans.getmore.live.amount-model must be NONE or RULE_AMOUNT_SINGLE_USE"
                + " (GETMORE_LIVE_AMOUNT_MODEL)")
        private LiveAmountModel amountModel = LiveAmountModel.NONE;
        @Pattern(regexp = "^[A-Z]{3}$",
                message = "loans.getmore.live.currency must be a 3-letter currency code (GETMORE_LIVE_CURRENCY)")
        private String currency = "USD";
        @Min(value = 1,
                message = "loans.getmore.live.connect-timeout-ms must be at least 1 (GETMORE_CONNECT_TIMEOUT_MS)")
        private int connectTimeoutMs = 2000;
        @Min(value = 1, message = "loans.getmore.live.read-timeout-ms must be at least 1 (GETMORE_READ_TIMEOUT_MS)")
        private int readTimeoutMs = 10000;
    }

    /** STAGING ONLY: the in-process stand-in store. */
    @Data
    public static class Simulator {
        @ToString.Exclude
        private String apiKey;
        /** The one node SIMULATED may run on (a deployment compares it with {@code node-name}). */
        private String allowedNode;
        @NotNull(message = "loans.getmore.simulator.coupon-prefix must be set (GETMORE_SIMULATOR_COUPON_PREFIX)")
        @Pattern(regexp = PREFIX_SHAPE,
                message = "loans.getmore.simulator.coupon-prefix must be 2 to 8 of A-Z and 0-9, then a dash, e.g. SIM-"
                        + " (GETMORE_SIMULATOR_COUPON_PREFIX)")
        private String couponPrefix = "SIM-";
        /** What the simulated store sells in. */
        @Pattern(regexp = "^[A-Z]{3}$", message = "loans.getmore.simulator.currency must be a 3-letter currency code"
                + " (GETMORE_SIMULATOR_CURRENCY)")
        private String currency = "USD";
        /** Assumption A2: a coupon spent over several visits; false, one purchase of exactly what is left. */
        private boolean partialSpending = true;
        /** Assumption A4: the till refuses a coupon whose {@code expiration_date} has passed. */
        private boolean enforceCouponExpiry = true;
    }
}
