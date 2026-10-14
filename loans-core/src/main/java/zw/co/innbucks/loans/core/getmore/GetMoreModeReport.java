package zw.co.innbucks.loans.core.getmore;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import zw.co.innbucks.loans.core.auth.JwtConfig;
import zw.co.innbucks.loans.core.config.DeploymentProfiles;
import zw.co.innbucks.loans.core.getmore.live.LiveAmountModel;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * GetMore's mode, checked and announced at boot, in the style of {@code VoucherCodeVault}: the constructor refuses to
 * start ({@link IllegalStateException}) on a configuration that could do harm, and says at every boot which mode this
 * host is in. It is a constructor dependency of the {@link GetMoreStore} bean, so it runs while the context refreshes,
 * before the web connector opens, and lazy initialisation cannot skip it. Every message names the variables, never a
 * value.
 *
 * <ul>
 *   <li><b>OFF</b> (the default): a WARN, and another if live or simulator keys are set (they are ignored).</li>
 *   <li><b>LIVE</b>: refuses a base URL that is blank or not https, any of the four OAuth values blank, an access token
 *       or its secret that is not exactly 32 characters (Magento's rule), a placeholder value outside
 *       dev/test/local/it, and any simulator key. INFO with the host, rule, prefix and amount model; a WARN while the
 *       amount model is NONE (LIVE then reads GetMore but issues nothing).</li>
 *   <li><b>SIMULATED</b> (staging only): refuses an api key under 32 characters or a placeholder, a key equal to any
 *       other secret this service holds, any live URL or OAuth value (a host provisioned for the real store never
 *       simulates) and, outside dev/test/local/it, a pod whose node is not {@code GETMORE_SIMULATOR_ALLOWED_NODE}:
 *       a staging env file copied to another host carries staging's node name, which that host's node does not have.
 *       ERROR at every boot.</li>
 * </ul>
 * The gauge {@value #GAUGE}{@code {mode}} reads 1 for the active mode and 0 for the others.
 */
@Slf4j
public class GetMoreModeReport {

    static final String GAUGE = "loans.getmore.mode";
    static final int MIN_SIMULATOR_KEY_LENGTH = 32;
    /** Magento refuses an integration token or token secret of any other length. */
    static final int MAGENTO_TOKEN_LENGTH = 32;

    /** The other secrets this service holds; the simulator's key must equal none of them. */
    private static final Map<String, String> OTHER_SECRETS = Map.of(
            "loans.borrower.test-assertions.api-key", "BORROWER_TEST_ASSERTIONS_API_KEY",
            "jwt.secret", "JWT_SECRET",
            "loans.vouchers.code-hmac-key", "VOUCHER_CODE_HMAC_KEY",
            "loans.vouchers.code-encryption-key", "VOUCHER_CODE_ENCRYPTION_KEY");

    private final GetMoreMode mode;

    public GetMoreModeReport(GetMoreProperties properties, Environment environment, MeterRegistry meterRegistry) {
        this.mode = properties.getMode();
        switch (mode) {
            case OFF -> reportOff(properties);
            case LIVE -> checkLive(properties, environment);
            case SIMULATED -> checkSimulated(properties, environment);
        }
        if (meterRegistry != null) {
            for (GetMoreMode each : GetMoreMode.values()) {
                int value = each == mode ? 1 : 0;
                Gauge.builder(GAUGE, () -> value)
                        .description("GetMore's mode on this host: 1 for the active one")
                        .tag("mode", each.name())
                        .register(meterRegistry);
            }
        }
    }

    /** The mode this host booted in. */
    public GetMoreMode mode() {
        return mode;
    }

    private static void reportOff(GetMoreProperties properties) {
        log.warn("GetMore is OFF: an accepted Staff Grocery Loan waits AWAITING_DISBURSEMENT with no voucher."
                + " Set GETMORE_MODE.");
        List<String> ignored = new ArrayList<>(liveValuesSet(properties.getLive()));
        ignored.addAll(simulatorValuesSet(properties.getSimulator()));
        if (!ignored.isEmpty()) {
            log.warn("GetMore is OFF, so {} {} ignored", String.join(", ", ignored),
                    ignored.size() == 1 ? "is" : "are");
        }
    }

    private static void checkLive(GetMoreProperties properties, Environment environment) {
        GetMoreProperties.Live live = properties.getLive();
        String baseUrl = trimmed(live.getBaseUrl());
        if (baseUrl.isEmpty() || !baseUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException("GETMORE_MODE is LIVE but GETMORE_BASE_URL is "
                    + (baseUrl.isEmpty() ? "not set" : "not an https:// URL")
                    + ": set it to GetMore's REST base, e.g. https://qa.getmore.syncrony.com/rest/V1");
        }
        Map<String, String> credentials = credentials(live);
        StringJoiner missing = new StringJoiner(", ");
        credentials.forEach((name, value) -> {
            if (trimmed(value).isEmpty()) {
                missing.add(name);
            }
        });
        if (missing.length() > 0) {
            throw new IllegalStateException("GETMORE_MODE is LIVE but " + missing + " not set: LIVE signs every call"
                    + " with all four of GETMORE_CONSUMER_KEY, GETMORE_CONSUMER_SECRET, GETMORE_ACCESS_TOKEN and"
                    + " GETMORE_ACCESS_TOKEN_SECRET");
        }
        for (String name : List.of("GETMORE_ACCESS_TOKEN", "GETMORE_ACCESS_TOKEN_SECRET")) {
            int length = trimmed(credentials.get(name)).length();
            if (length != MAGENTO_TOKEN_LENGTH) {
                throw new IllegalStateException(name + " must be exactly " + MAGENTO_TOKEN_LENGTH
                        + " characters, as Magento issues it, not " + length);
            }
        }
        if (DeploymentProfiles.isDeployment(environment)) {
            credentials.forEach((name, value) -> {
                if (JwtConfig.isPlaceholder(value)) {
                    throw new IllegalStateException(name + " is a development placeholder and no dev/test/local/it"
                            + " profile is active; set the integration's real value");
                }
            });
        }
        List<String> simulator = simulatorValuesSet(properties.getSimulator());
        if (!simulator.isEmpty()) {
            throw new IllegalStateException("GETMORE_MODE is LIVE but " + String.join(" and ", simulator)
                    + (simulator.size() == 1 ? " is" : " are") + " set: a host that sells GetMore's real coupons"
                    + " never carries the simulator's keys. Remove them.");
        }
        log.info("GetMore is LIVE at {}, rule {}, prefix {}, amount model {}", host(baseUrl), properties.getRuleId(),
                properties.getCouponPrefix(), live.getAmountModel());
        if (live.getAmountModel() == LiveAmountModel.NONE) {
            log.warn("GetMore: LIVE reads GetMore but issues no voucher until GETMORE_LIVE_AMOUNT_MODEL is set:"
                    + " GetMore has not said how a per-loan amount is held");
        }
    }

    private static void checkSimulated(GetMoreProperties properties, Environment environment) {
        GetMoreProperties.Simulator simulator = properties.getSimulator();
        String key = trimmed(simulator.getApiKey());
        if (key.length() < MIN_SIMULATOR_KEY_LENGTH) {
            throw new IllegalStateException("GETMORE_MODE is SIMULATED but GETMORE_SIMULATOR_API_KEY is "
                    + (key.isEmpty() ? "not set" : "shorter than " + MIN_SIMULATOR_KEY_LENGTH + " characters")
                    + ": generate one with `openssl rand -base64 48`");
        }
        if (JwtConfig.isPlaceholder(key)) {
            throw new IllegalStateException("GETMORE_SIMULATOR_API_KEY is a development placeholder; generate one with"
                    + " `openssl rand -base64 48`");
        }
        OTHER_SECRETS.forEach((property, variable) -> {
            String other = trimmed(environment.getProperty(property));
            if (!other.isEmpty() && other.equals(key)) {
                throw new IllegalStateException("GETMORE_SIMULATOR_API_KEY must differ from " + variable
                        + ": one leaked key must never open two doors");
            }
        });
        List<String> live = liveValuesSet(properties.getLive());
        if (!live.isEmpty()) {
            throw new IllegalStateException("GETMORE_MODE is SIMULATED but " + String.join(", ", live)
                    + (live.size() == 1 ? " is" : " are") + " set: a host provisioned for the real store never"
                    + " simulates. Remove them, or set GETMORE_MODE=LIVE.");
        }
        String node = trimmed(properties.getNodeName());
        if (DeploymentProfiles.isDeployment(environment)) {
            String allowed = trimmed(simulator.getAllowedNode());
            if (allowed.isEmpty() || !allowed.equals(node)) {
                throw new IllegalStateException("GETMORE_SIMULATOR_ALLOWED_NODE does not name this node: a staging env"
                        + " file on another host never simulates. GETMORE_SIMULATOR_ALLOWED_NODE is "
                        + (allowed.isEmpty() ? "not set" : "set") + " and this pod's NODE_NAME is "
                        + (node.isEmpty() ? "not set (the Deployment's spec.nodeName fieldRef)" : "'" + node + "'"));
            }
        }
        log.error("GETMORE IS SIMULATED on node {}: Staff Grocery Loan vouchers are coupons of a FAKE in-process store"
                + " (codes start {}), and /lending/v1/getmore-simulator/** spends them for whoever holds"
                + " GETMORE_SIMULATOR_API_KEY. Staging only; never where salaries are collected. Turn off with"
                + " GETMORE_MODE=OFF. Simulator assumptions, not Magento: A1 each coupon is worth its own allowance;"
                + " A2 it is spent in parts (partial spending {}); A3 a refund restores the balance; A4 the coupon's"
                + " expiration_date is enforced at the till (enforcement {}).", node.isEmpty() ? "(unnamed)" : node,
                simulator.getCouponPrefix(), simulator.isPartialSpending() ? "on" : "off",
                simulator.isEnforceCouponExpiry() ? "on" : "off");
    }

    private static Map<String, String> credentials(GetMoreProperties.Live live) {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("GETMORE_CONSUMER_KEY", live.getConsumerKey());
        credentials.put("GETMORE_CONSUMER_SECRET", live.getConsumerSecret());
        credentials.put("GETMORE_ACCESS_TOKEN", live.getAccessToken());
        credentials.put("GETMORE_ACCESS_TOKEN_SECRET", live.getAccessTokenSecret());
        return credentials;
    }

    /** The live URL and OAuth variables that are set, by name. */
    private static List<String> liveValuesSet(GetMoreProperties.Live live) {
        List<String> set = new ArrayList<>();
        if (!trimmed(live.getBaseUrl()).isEmpty()) {
            set.add("GETMORE_BASE_URL");
        }
        if (!trimmed(live.getSignatureBaseUrl()).isEmpty()) {
            set.add("GETMORE_SIGNATURE_BASE_URL");
        }
        credentials(live).forEach((name, value) -> {
            if (!trimmed(value).isEmpty()) {
                set.add(name);
            }
        });
        return set;
    }

    /** The simulator's key and node variables that are set, by name. */
    private static List<String> simulatorValuesSet(GetMoreProperties.Simulator simulator) {
        List<String> set = new ArrayList<>();
        if (!trimmed(simulator.getApiKey()).isEmpty()) {
            set.add("GETMORE_SIMULATOR_API_KEY");
        }
        if (!trimmed(simulator.getAllowedNode()).isEmpty()) {
            set.add("GETMORE_SIMULATOR_ALLOWED_NODE");
        }
        return set;
    }

    private static String host(String url) {
        try {
            return Objects.requireNonNullElse(URI.create(url).getHost(), url);
        } catch (IllegalArgumentException unparsable) {
            return "(an unparsable URL)";
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.strip();
    }
}
