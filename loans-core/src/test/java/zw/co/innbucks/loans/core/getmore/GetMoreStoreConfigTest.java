package zw.co.innbucks.loans.core.getmore;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.config.OutboundHttp;
import zw.co.innbucks.loans.core.getmore.live.MagentoGetMoreStore;
import zw.co.innbucks.loans.core.getmore.live.NoLiveAmountModel;
import zw.co.innbucks.loans.core.getmore.live.RuleAmountSingleUseBinding;
import zw.co.innbucks.loans.core.getmore.simulator.SimRuleRepository;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedAmountBinding;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedGetMoreStore;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedStoreBackend;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedStoreSeeder;
import zw.co.innbucks.loans.core.voucher.GetMoreDataGuard;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorFaults;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * GetMore's mode switch and boot rules, over {@link GetMoreStoreConfig} alone. Exactly one store and one amount
 * binding in every mode; OFF by default; LIVE refuses an incomplete or simulator-tainted configuration; SIMULATED refuses
 * a weak or shared key, any live credential, and (outside dev/test/local/it) a node it was not allowed on, and says so
 * at ERROR on every boot. No case prints a key. With no profile active the runner is a deployment, as a container
 * started without {@code SPRING_PROFILES_ACTIVE} is.
 */
@ExtendWith(OutputCaptureExtension.class)
class GetMoreStoreConfigTest {

    private static final String TOKEN = "tk0123456789abcdefghijklmnopqrst";
    private static final String TOKEN_SECRET = "ts0123456789abcdefghijklmnopqrst";
    private static final String CONSUMER_KEY = "ck0123456789abcdefghijklmnopqrst";
    private static final String CONSUMER_SECRET = "cs0123456789abcdefghijklmnopqrst";
    private static final String SIM_KEY = "s1mK3y-9f8e7d6c5b4a39281706f5e4d3c2b1a0ffeeddcc";
    private static final String NODE = "ip-10-0-146-246";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GetMoreStoreConfig.class)
            // A pool of its own per context: the context closes its OutboundHttp bean (it is AutoCloseable), so the
            // shared test pool must never be handed to it.
            .withBean(OutboundHttp.class, OutboundHttp::withDefaults)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    private static String[] live(String... more) {
        List<String> values = new ArrayList<>(List.of(
                "loans.getmore.mode=LIVE",
                "loans.getmore.live.base-url=https://qa.getmore.syncrony.com/rest/V1",
                "loans.getmore.live.consumer-key=" + CONSUMER_KEY,
                "loans.getmore.live.consumer-secret=" + CONSUMER_SECRET,
                "loans.getmore.live.access-token=" + TOKEN,
                "loans.getmore.live.access-token-secret=" + TOKEN_SECRET));
        values.addAll(Arrays.asList(more));
        return values.toArray(String[]::new);
    }

    private static String[] simulated(String... more) {
        List<String> values = new ArrayList<>(List.of(
                "loans.getmore.mode=SIMULATED",
                "loans.getmore.simulator.api-key=" + SIM_KEY,
                "loans.getmore.simulator.allowed-node=" + NODE));
        values.addAll(Arrays.asList(more));
        return values.toArray(String[]::new);
    }

    /** The simulator's beans exist only in SIMULATED; the store needs them there. The pod runs on {@link #NODE}. */
    private ApplicationContextRunner withSimulatorBeans() {
        return withSimulatorBeans(NODE);
    }

    /** As {@link #withSimulatorBeans()}, on a pod whose {@code NODE_NAME} is {@code node} (null: not set). */
    private ApplicationContextRunner withSimulatorBeans(String node) {
        return runner.withBean(SimulatedStoreBackend.class, () -> mock(SimulatedStoreBackend.class))
                .withBean(SimulatorFaults.class, SimulatorFaults::new)
                .withBean(GetMoreModeReport.NodeName.class, () -> () -> node);
    }

    /** A value exactly as the environment would hand it over: the runner's own pairs are trimmed. */
    private static ApplicationContextRunner raw(ApplicationContextRunner runner, String property, String value) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("raw-" + property, Map.of(property, value))));
    }

    private static ApplicationContextRunner profiles(ApplicationContextRunner runner, String... profiles) {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles));
    }

    private static void refused(AssertableApplicationContext context, String... messageParts) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).rootCause().satisfies(cause -> {
            assertThat(cause.getMessage()).contains(messageParts);
            assertThat(cause.getMessage()).doesNotContain(SIM_KEY, TOKEN, TOKEN_SECRET, CONSUMER_SECRET);
        });
    }

    // ---- OFF ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("OFF by default: a store that refuses every call as OFF, the NONE amount model, and a WARN")
    void offByDefault(CapturedOutput output) {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GetMoreStore.class)
                    .hasSingleBean(GetMoreAmountBinding.class);
            GetMoreStore store = context.getBean(GetMoreStore.class);
            assertThat(store).isInstanceOf(DisabledGetMoreStore.class);
            assertThat(store.mode()).isEqualTo(GetMoreMode.OFF);
            assertThatThrownBy(() -> store.salesRule(43)).isInstanceOfSatisfying(GetMoreUnavailableException.class,
                    e -> assertThat(e.reason()).isEqualTo(GetMoreUnavailableException.Reason.OFF));
            assertThat(context.getBean(GetMoreAmountBinding.class)).isInstanceOf(NoLiveAmountModel.class);
            assertThat(gauge(context, GetMoreMode.OFF)).isEqualTo(1.0);
            assertThat(gauge(context, GetMoreMode.LIVE)).isZero();
            assertThat(gauge(context, GetMoreMode.SIMULATED)).isZero();
        });
        assertThat(output).contains("GetMore is OFF: an accepted Staff Grocery Loan waits AWAITING_DISBURSEMENT with"
                + " no voucher. Set GETMORE_MODE.");
    }

    @Test
    @DisplayName("OFF with live or simulator keys set: a second WARN names them as ignored, never their values")
    void offWarnsAboutIgnoredKeys(CapturedOutput output) {
        runner.withPropertyValues("loans.getmore.live.base-url=https://qa.getmore.syncrony.com/rest/V1",
                        "loans.getmore.live.consumer-secret=" + CONSUMER_SECRET,
                        "loans.getmore.simulator.api-key=" + SIM_KEY)
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output).contains("GetMore is OFF, so GETMORE_BASE_URL, GETMORE_CONSUMER_SECRET,"
                + " GETMORE_SIMULATOR_API_KEY are ignored").doesNotContain(CONSUMER_SECRET, SIM_KEY);
    }

    // ---- LIVE --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("LIVE, complete: the Magento store, INFO with the host, and a WARN while the amount model is NONE")
    void liveComplete(CapturedOutput output) {
        runner.withPropertyValues(live()).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GetMoreStore.class)
                    .hasSingleBean(GetMoreAmountBinding.class);
            assertThat(context.getBean(GetMoreStore.class)).isInstanceOf(MagentoGetMoreStore.class);
            assertThat(context.getBean(GetMoreAmountBinding.class)).isInstanceOf(NoLiveAmountModel.class);
            assertThat(gauge(context, GetMoreMode.LIVE)).isEqualTo(1.0);
        });
        assertThat(output).contains("GetMore is LIVE at qa.getmore.syncrony.com, rule 43, prefix INN-, amount model"
                        + " NONE")
                .contains("LIVE reads GetMore but issues no voucher until GETMORE_LIVE_AMOUNT_MODEL is set")
                .doesNotContain(TOKEN, TOKEN_SECRET, CONSUMER_SECRET);
    }

    @Test
    @DisplayName("LIVE with RULE_AMOUNT_SINGLE_USE: that binding, and no amount-model WARN")
    void liveRuleAmount(CapturedOutput output) {
        runner.withPropertyValues(live("loans.getmore.live.amount-model=RULE_AMOUNT_SINGLE_USE")).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GetMoreAmountBinding.class);
            assertThat(context.getBean(GetMoreAmountBinding.class)).isInstanceOf(RuleAmountSingleUseBinding.class);
        });
        assertThat(output).doesNotContain("issues no voucher until GETMORE_LIVE_AMOUNT_MODEL is set");
    }

    @Test
    @DisplayName("LIVE refuses each missing OAuth value, naming its variable")
    void liveRefusesMissingKeys() {
        for (String key : List.of("consumer-key", "consumer-secret", "access-token", "access-token-secret")) {
            String variable = "GETMORE_" + key.toUpperCase().replace('-', '_');
            runner.withPropertyValues(live("loans.getmore.live." + key + "="))
                    .run(context -> refused(context, variable, "not set"));
        }
    }

    @Test
    @DisplayName("LIVE refuses a blank base URL, or one that is not a valid https URL a request can be built on")
    void liveRefusesBaseUrl() {
        runner.withPropertyValues(live("loans.getmore.live.base-url=http://qa.getmore.syncrony.com/rest/V1"))
                .run(context -> refused(context, "GETMORE_BASE_URL", "not a valid https URL"));
        runner.withPropertyValues(live("loans.getmore.live.base-url="))
                .run(context -> refused(context, "GETMORE_BASE_URL", "not set"));
        for (String bad : List.of("https://", "https:///rest/V1", "https://qa.getmore syncrony.com/rest/V1",
                "https://qa.getmore.syncrony.com/rest/V1?store=default", "https://qa.getmore.syncrony.com/rest/V1#x",
                "qa.getmore.syncrony.com/rest/V1")) {
            runner.withPropertyValues(live("loans.getmore.live.base-url=" + bad))
                    .run(context -> refused(context, "GETMORE_BASE_URL", "not a valid https URL"));
        }
        raw(runner.withPropertyValues(live()), "loans.getmore.live.base-url",
                "https://qa.getmore.syncrony.com/rest/V1\r")
                .run(context -> refused(context, "GETMORE_BASE_URL", "not a valid https URL"));
        runner.withPropertyValues(live("loans.getmore.live.base-url=HTTPS://qa.getmore.syncrony.com/rest/V1/"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("LIVE refuses a signature base URL that is set and not a valid http or https URL")
    void liveRefusesSignatureBaseUrl() {
        for (String bad : List.of("qa.getmore.syncrony.com/rest/V1", "http://", "ftp://qa.getmore.syncrony.com/rest/V1",
                "http://qa.getmore syncrony.com/rest/V1")) {
            runner.withPropertyValues(live("loans.getmore.live.signature-base-url=" + bad))
                    .run(context -> refused(context, "GETMORE_SIGNATURE_BASE_URL is not a valid http or https URL"));
        }
        runner.withPropertyValues(live("loans.getmore.live.signature-base-url=http://qa.getmore.syncrony.com/rest/V1"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("LIVE refuses an OAuth value with whitespace around it (a secret file's newline, a CRLF env file): the"
            + " signer would sign with those bytes")
    void liveRefusesWhitespaceAroundCredentials() {
        raw(runner.withPropertyValues(live()), "loans.getmore.live.access-token", TOKEN + "\n")
                .run(context -> refused(context, "GETMORE_ACCESS_TOKEN has leading or trailing whitespace"));
        raw(runner.withPropertyValues(live()), "loans.getmore.live.consumer-secret", CONSUMER_SECRET + "\r")
                .run(context -> refused(context, "GETMORE_CONSUMER_SECRET has leading or trailing whitespace"));
        raw(runner.withPropertyValues(live()), "loans.getmore.live.consumer-key", " " + CONSUMER_KEY)
                .run(context -> refused(context, "GETMORE_CONSUMER_KEY has leading or trailing whitespace"));
    }

    @Test
    @DisplayName("LIVE refuses an access token or token secret that is not exactly 32 characters")
    void liveRefusesTokenLength() {
        runner.withPropertyValues(live("loans.getmore.live.access-token=" + TOKEN.substring(1)))
                .run(context -> refused(context, "GETMORE_ACCESS_TOKEN must be exactly 32 characters", "not 31"));
        runner.withPropertyValues(live("loans.getmore.live.access-token-secret=" + TOKEN_SECRET + "x"))
                .run(context -> refused(context, "GETMORE_ACCESS_TOKEN_SECRET must be exactly 32 characters"));
    }

    @Test
    @DisplayName("LIVE refuses a placeholder value on a deployment, and allows it under the test profile")
    void liveRefusesPlaceholderOnDeployment() {
        String placeholder = "cs-change-me-0123456789abcdefghij";
        runner.withPropertyValues(live("loans.getmore.live.consumer-secret=" + placeholder))
                .run(context -> refused(context, "GETMORE_CONSUMER_SECRET is a development placeholder"));
        profiles(runner, "api").withPropertyValues(live("loans.getmore.live.consumer-secret=" + placeholder))
                .run(context -> assertThat(context).hasFailed());
        profiles(runner, "test").withPropertyValues(live("loans.getmore.live.consumer-secret=" + placeholder))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("LIVE refuses either simulator key: a host that sells real coupons never carries them")
    void liveRefusesSimulatorKeys() {
        runner.withPropertyValues(live("loans.getmore.simulator.api-key=" + SIM_KEY))
                .run(context -> refused(context, "GETMORE_SIMULATOR_API_KEY"));
        runner.withPropertyValues(live("loans.getmore.simulator.allowed-node=" + NODE))
                .run(context -> refused(context, "GETMORE_SIMULATOR_ALLOWED_NODE"));
    }

    // ---- SIMULATED ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("SIMULATED on its allowed node: the simulated store and binding, the gauge, and the ERROR line")
    void simulatedOnItsNode(CapturedOutput output) {
        withSimulatorBeans().withPropertyValues(simulated()).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GetMoreStore.class)
                    .hasSingleBean(GetMoreAmountBinding.class);
            assertThat(context.getBean(GetMoreStore.class)).isInstanceOf(SimulatedGetMoreStore.class);
            assertThat(context.getBean(GetMoreStore.class).mode()).isEqualTo(GetMoreMode.SIMULATED);
            GetMoreAmountBinding binding = context.getBean(GetMoreAmountBinding.class);
            assertThat(binding).isInstanceOf(SimulatedAmountBinding.class);
            assertThat(binding.name()).isEqualTo("SIMULATED_PER_COUPON");
            assertThat(binding.partialSpending()).isTrue();
            assertThat(gauge(context, GetMoreMode.SIMULATED)).isEqualTo(1.0);
            assertThat(gauge(context, GetMoreMode.OFF)).isZero();
        });
        assertThat(output).contains("ERROR")
                .contains("GETMORE IS SIMULATED on node " + NODE + ": Staff Grocery Loan vouchers are coupons of a"
                        + " FAKE in-process store (codes start SIM-), and /lending/v1/getmore-simulator/** spends them"
                        + " for whoever holds GETMORE_SIMULATOR_API_KEY. Staging only; never where salaries are"
                        + " collected. Turn off with GETMORE_MODE=OFF.")
                .doesNotContain(SIM_KEY);
    }

    @Test
    @DisplayName("SIMULATED refuses a key shorter than 32 characters, or a placeholder")
    void simulatedRefusesWeakKey() {
        withSimulatorBeans().withPropertyValues(simulated("loans.getmore.simulator.api-key=" + "k".repeat(31)))
                .run(context -> refused(context, "GETMORE_SIMULATOR_API_KEY", "shorter than 32"));
        withSimulatorBeans().withPropertyValues(simulated("loans.getmore.simulator.api-key="))
                .run(context -> refused(context, "GETMORE_SIMULATOR_API_KEY", "not set"));
        withSimulatorBeans().withPropertyValues(simulated("loans.getmore.simulator.api-key=change-me-"
                        + "0123456789abcdefghijklmnopqrstuvw"))
                .run(context -> refused(context, "GETMORE_SIMULATOR_API_KEY is a development placeholder"));
    }

    @Test
    @DisplayName("SIMULATED refuses a key equal to any other secret: its own, and the keys partners already hold")
    void simulatedRefusesSharedKey() {
        Map<String, String> others = Map.of(
                "loans.borrower.test-assertions.api-key", "BORROWER_TEST_ASSERTIONS_API_KEY",
                "jwt.secret", "JWT_SECRET",
                "loans.vouchers.code-hmac-key", "VOUCHER_CODE_HMAC_KEY",
                "loans.vouchers.code-encryption-key", "VOUCHER_CODE_ENCRYPTION_KEY",
                "monitoring.scrape-token", "METRICS_SCRAPE_TOKEN",
                "whatsapp.api-key", "WHATSAPP_API_KEY",
                "innbucks-notify.api-key", "INNBUCKS_NOTIFY_API_KEY",
                "innbucks.api-key", "INNBUCKS_API_KEY");
        others.forEach((other, variable) -> withSimulatorBeans().withPropertyValues(simulated(other + "=" + SIM_KEY))
                .run(context -> refused(context, "GETMORE_SIMULATOR_API_KEY must differ from " + variable)));
        withSimulatorBeans().withPropertyValues(simulated("monitoring.scrape-token=another-" + SIM_KEY))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("SIMULATED refuses any live URL or OAuth value: a host provisioned for the real store never simulates")
    void simulatedRefusesLiveCredentials() {
        for (String live : List.of("base-url=https://qa.getmore.syncrony.com/rest/V1",
                "signature-base-url=http://qa.getmore.syncrony.com/rest/V1", "consumer-key=" + CONSUMER_KEY,
                "consumer-secret=" + CONSUMER_SECRET, "access-token=" + TOKEN,
                "access-token-secret=" + TOKEN_SECRET)) {
            withSimulatorBeans().withPropertyValues(simulated("loans.getmore.live." + live))
                    .run(context -> refused(context, "a host provisioned for the real store never simulates"));
        }
    }

    @Test
    @DisplayName("SIMULATED on a deployment refuses a blank or different allowed node; under test it is not checked")
    void simulatedRefusesAnotherNode() {
        withSimulatorBeans("ip-10-0-9-9").withPropertyValues(simulated())
                .run(context -> refused(context, "GETMORE_SIMULATOR_ALLOWED_NODE does not name this node"));
        withSimulatorBeans().withPropertyValues(simulated("loans.getmore.simulator.allowed-node="))
                .run(context -> refused(context, "GETMORE_SIMULATOR_ALLOWED_NODE does not name this node"));
        withSimulatorBeans(null).withPropertyValues(simulated())
                .run(context -> refused(context, "NODE_NAME is not set"));
        profiles(withSimulatorBeans(null), "api", "test")
                .withPropertyValues(simulated("loans.getmore.simulator.allowed-node="))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("SIMULATED: no property can name the node (a copied env file's LOANS_GETMORE_NODENAME, a"
            + " SPRING_APPLICATION_JSON entry); only the pod's own NODE_NAME counts")
    void simulatedNodeIsNeverReadFromAProperty() {
        withSimulatorBeans(null).withPropertyValues(simulated("loans.getmore.node-name=" + NODE,
                        "loans.getmore.nodename=" + NODE, "NODE_NAME=" + NODE))
                .run(context -> refused(context, "NODE_NAME is not set"));
        assertThat(GetMoreModeReport.NodeName.FROM_ENVIRONMENT.get()).isEqualTo(System.getenv("NODE_NAME"));
    }

    @Test
    @DisplayName("SIMULATED refused on the wrong node: the seeder never writes, because the boot checks run first")
    void seederNeverWritesBeforeTheBootChecks() {
        SimRuleRepository rules = mock(SimRuleRepository.class);
        // Registered before the configuration's beans, so without its dependency on the mode report the seeder would
        // be created, and would seed, first.
        withSimulatorBeans("ip-10-0-9-9")
                .withBean(SimRuleRepository.class, () -> rules)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(MarketTimeZone.class, () -> new MarketTimeZone("ZW", Clock.systemUTC()))
                .withBean(GetMoreDataGuard.class, () -> mock(GetMoreDataGuard.class))
                .withBean(SimulatedStoreSeeder.class)
                .withPropertyValues(simulated())
                .run(context -> refused(context, "GETMORE_SIMULATOR_ALLOWED_NODE does not name this node"));

        verify(rules, never()).seedIfAbsent(anyLong(), any(), any());
    }

    @Test
    @DisplayName("SIMULATED on its node: the seeder seeds, after the boot checks")
    void seederSeedsOnItsNode() {
        SimRuleRepository rules = mock(SimRuleRepository.class);
        withSimulatorBeans()
                .withBean(SimRuleRepository.class, () -> rules)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(MarketTimeZone.class, () -> new MarketTimeZone("ZW", Clock.systemUTC()))
                .withBean(GetMoreDataGuard.class, () -> mock(GetMoreDataGuard.class))
                .withBean(SimulatedStoreSeeder.class)
                .withPropertyValues(simulated())
                .run(context -> assertThat(context).hasNotFailed());

        verify(rules).seedIfAbsent(anyLong(), any(), any());
    }

    @Test
    @DisplayName("SIMULATED without the simulator's beans (the mode not read as SIMULATED by them): refused")
    void simulatedWithoutItsBeansRefused() {
        runner.withBean(GetMoreModeReport.NodeName.class, () -> () -> NODE).withPropertyValues(simulated())
                .run(context -> refused(context, "the simulator's beans were not created"));
    }

    // ---- shape validation ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("the live and simulator prefixes must differ, and each be 2 to 8 of A-Z 0-9 then a dash")
    void prefixesValidated() {
        runner.withPropertyValues("loans.getmore.simulator.coupon-prefix=INN-")
                .run(context -> refused(context, "must differ"));
        runner.withPropertyValues("loans.getmore.coupon-prefix=inn-")
                .run(context -> refused(context, "loans.getmore.coupon-prefix must be 2 to 8"));
        runner.withPropertyValues("loans.getmore.simulator.coupon-prefix=SIMULATOR1-")
                .run(context -> refused(context, "loans.getmore.simulator.coupon-prefix must be 2 to 8"));
    }

    @Test
    @DisplayName("a code must fit one SMS: prefix + 9 + code length at most 39, for both prefixes")
    void codeFitsOneMessage() {
        runner.withPropertyValues("loans.getmore.code-length=26")
                .run(context -> assertThat(context).hasNotFailed());   // INN- and SIM-: 4 + 9 + 26 = 39
        runner.withPropertyValues("loans.getmore.code-length=27")
                .run(context -> refused(context, "must fit one SMS"));
        runner.withPropertyValues("loans.getmore.coupon-prefix=INNBUCKS-", "loans.getmore.code-length=21")
                .run(context -> assertThat(context).hasNotFailed());   // 9 + 9 + 21 = 39
        runner.withPropertyValues("loans.getmore.coupon-prefix=INNBUCKS-", "loans.getmore.code-length=22")
                .run(context -> refused(context, "must fit one SMS"));
        runner.withPropertyValues("loans.getmore.simulator.coupon-prefix=SIMULAT-", "loans.getmore.code-length=23")
                .run(context -> refused(context, "must fit one SMS"));   // the simulator's: 8 + 9 + 23 = 40
    }

    @Test
    @DisplayName("the stale claim must outlast eight calls at their timeouts (112s with the defaults)")
    void staleClaimLongEnough() {
        runner.withPropertyValues("loans.getmore.issuance.stale-claim=PT112S")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("loans.getmore.issuance.stale-claim=PT111S")
                .run(context -> refused(context, "loans.getmore.issuance.stale-claim must be at least 8 x"));
        runner.withPropertyValues("loans.getmore.live.read-timeout-ms=30000")
                .run(context -> assertThat(context).hasNotFailed());   // 8 x 34s = 272s, within PT5M
        runner.withPropertyValues("loans.getmore.live.read-timeout-ms=40000")
                .run(context -> refused(context, "stale-claim"));
    }

    @Test
    @DisplayName("rule id, page size, code length and the clock skew are bounded")
    void numbersBounded() {
        runner.withPropertyValues("loans.getmore.page-size=301")
                .run(context -> refused(context, "loans.getmore.page-size must be at most 300"));
        runner.withPropertyValues("loans.getmore.rule-id=0")
                .run(context -> refused(context, "loans.getmore.rule-id"));
        runner.withPropertyValues("loans.getmore.code-length=11")
                .run(context -> refused(context, "loans.getmore.code-length must be at least 12"));
        runner.withPropertyValues("loans.getmore.code-length=12")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("loans.getmore.clock-skew=")   // a blank GETMORE_CLOCK_SKEW keeps the default
                .run(context -> assertThat(context.getBean(GetMoreProperties.class).getClockSkew())
                        .isEqualTo(Duration.ofMinutes(2)));
        runner.withPropertyValues("loans.getmore.clock-skew=-PT1M")
                .run(context -> refused(context, "loans.getmore.clock-skew must not be negative"));
        runner.withPropertyValues("loans.getmore.clock-skew=PT0S")
                .run(context -> assertThat(context).hasNotFailed());
        GetMoreProperties nulled = new GetMoreProperties();
        nulled.setClockSkew(null);
        try (ValidatorFactory validation = Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(nulled)).extracting(ConstraintViolation::getMessage)
                    .containsExactly("loans.getmore.clock-skew must be set (GETMORE_CLOCK_SKEW)");
        }
    }

    private static double gauge(AssertableApplicationContext context, GetMoreMode mode) {
        return context.getBean(MeterRegistry.class).get(GetMoreModeReport.GAUGE).tag("mode", mode.name()).gauge()
                .value();
    }
}
