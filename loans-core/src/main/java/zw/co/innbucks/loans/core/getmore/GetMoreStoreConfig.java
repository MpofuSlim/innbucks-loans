package zw.co.innbucks.loans.core.getmore;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;
import zw.co.innbucks.loans.core.config.OutboundHttp;
import zw.co.innbucks.loans.core.getmore.live.MagentoGetMoreStore;
import zw.co.innbucks.loans.core.getmore.live.MagentoOAuth1Signer;
import zw.co.innbucks.loans.core.getmore.live.NoLiveAmountModel;
import zw.co.innbucks.loans.core.getmore.live.RuleAmountSingleUseBinding;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedAmountBinding;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedGetMoreStore;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedStoreBackend;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorFaults;

import java.time.Clock;

/**
 * GetMore's wiring. {@code GETMORE_MODE} picks the one {@link GetMoreStore} and the one {@link GetMoreAmountBinding}
 * by an explicit switch, never {@code @ConditionalOnMissingBean}: every implementation is a plain class built here,
 * so exactly one of each exists. The store depends on {@link GetMoreModeReport}, so the boot checks run while the
 * context refreshes, before the web connector opens. The client is built in every mode, like the WhatsApp one; the
 * contract tests build it through this same method.
 */
@Configuration
@EnableConfigurationProperties(GetMoreProperties.class)
public class GetMoreStoreConfig {

    /**
     * On the service's one pool with GetMore's own timeouts, and with redirects OFF: a redirect would replay the
     * signed {@code Authorization} header against a path it was not signed for. No base URL: every call passes an
     * absolute {@link java.net.URI}, exactly the one it signed.
     */
    @Bean("getMoreRestClient")
    public static RestClient getMoreRestClient(GetMoreProperties properties, OutboundHttp outboundHttp) {
        return RestClient.builder()
                .requestFactory(outboundHttp.requestFactory(properties.getLive().getConnectTimeoutMs(),
                        properties.getLive().getReadTimeoutMs(), false))
                .build();
    }

    /**
     * The boot checks and the mode's announcement; refuses to start on a configuration that could do harm. The pod's
     * node is read from the process environment ({@link GetMoreModeReport.NodeName#FROM_ENVIRONMENT}); only a test
     * supplies a bean in its place.
     */
    @Bean
    public GetMoreModeReport getMoreModeReport(GetMoreProperties properties, Environment environment,
                                               ObjectProvider<MeterRegistry> meterRegistry,
                                               ObjectProvider<GetMoreModeReport.NodeName> nodeName) {
        return new GetMoreModeReport(properties, environment, meterRegistry.getIfAvailable(),
                nodeName.getIfAvailable(() -> GetMoreModeReport.NodeName.FROM_ENVIRONMENT));
    }

    /**
     * OFF: a store that refuses every call. LIVE: GetMore's Magento over OAuth 1.0a. SIMULATED: the in-process
     * stand-in, whose beans exist only in that mode (absent, the boot fails here rather than run a half-built
     * simulator).
     */
    @Bean
    public GetMoreStore getMoreStore(GetMoreProperties properties,
                                     @Qualifier("getMoreRestClient") RestClient restClient,
                                     GetMoreModeReport modeReport,
                                     ObjectProvider<SimulatedStoreBackend> simulatedBackend,
                                     ObjectProvider<SimulatorFaults> simulatorFaults,
                                     ObjectProvider<MeterRegistry> meterRegistry) {
        return switch (modeReport.mode()) {
            case OFF -> new DisabledGetMoreStore();
            case LIVE -> new MagentoGetMoreStore(restClient, properties.getLive(),
                    new MagentoOAuth1Signer(properties.getLive(), Clock.systemUTC(), MagentoOAuth1Signer::secureNonce),
                    meterRegistry.getIfAvailable());
            case SIMULATED -> new SimulatedGetMoreStore(required(simulatedBackend.getIfAvailable()),
                    required(simulatorFaults.getIfAvailable()));
        };
    }

    /**
     * SIMULATED: each coupon's own allowance. Otherwise {@code GETMORE_LIVE_AMOUNT_MODEL}: NONE (the default) refuses
     * before any generate; RULE_AMOUNT_SINGLE_USE uses the rule's own amount, one purchase per coupon.
     */
    @Bean
    public GetMoreAmountBinding getMoreAmountBinding(GetMoreProperties properties,
                                                     ObjectProvider<SimulatedStoreBackend> simulatedBackend) {
        if (properties.getMode() == GetMoreMode.SIMULATED) {
            return new SimulatedAmountBinding(simulatedBackend, properties.getSimulator());
        }
        return switch (properties.getLive().getAmountModel()) {
            case NONE -> new NoLiveAmountModel();
            case RULE_AMOUNT_SINGLE_USE -> new RuleAmountSingleUseBinding(properties.getLive().getCurrency());
        };
    }

    private static <T> T required(T simulatorBean) {
        if (simulatorBean == null) {
            throw new IllegalStateException("GETMORE_MODE is SIMULATED but the simulator's beans were not created:"
                    + " loans.getmore.mode must read exactly SIMULATED");
        }
        return simulatorBean;
    }
}
