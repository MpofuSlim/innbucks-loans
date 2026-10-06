package zw.co.innbucks.loans.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.AbstractClientHttpRequestFactoryWrapper;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import zw.co.innbucks.loans.core.notifications.InnbucksNotifyClientConfig;
import zw.co.innbucks.loans.core.notifications.InnbucksNotifyProperties;
import zw.co.innbucks.loans.core.notifications.WhatsAppClientConfig;
import zw.co.innbucks.loans.core.notifications.WhatsAppProperties;
import zw.co.innbucks.loans.core.testsupport.TestOutboundHttp;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every outbound client in loans draws on the ONE pool and keeps its own timeouts: the RestTemplate
 * that carries every Ndasenda and InnBucks call (booking, deposit, lodgement), and the notification
 * API and WhatsApp RestClients. A client built on a default factory, or a fresh
 * {@code SimpleClientHttpRequestFactory}, fails here.
 */
class OutboundHttpWiringTest {

    private static final OutboundHttp POOL = TestOutboundHttp.POOL;

    @Test
    @DisplayName("the shared RestTemplate (Ndasenda + InnBucks): pooled, with http.client.* timeouts, under the buffering wrapper")
    void restTemplate() {
        HttpClientConfig timeouts = new HttpClientConfig();
        timeouts.setConnectTimeout(10_000);
        timeouts.setReadTimeout(60_000);
        RestTemplate template = new RestConfig().restTemplate(new LoggingInterceptor(), timeouts, POOL);

        ClientHttpRequestFactory intercepting = template.getRequestFactory();
        ClientHttpRequestFactory buffering = ((AbstractClientHttpRequestFactoryWrapper) intercepting).getDelegate();
        ClientHttpRequestFactory pooled = ((AbstractClientHttpRequestFactoryWrapper) buffering).getDelegate();
        assertPooled(pooled, 10_000, 60_000);
    }

    @Test
    @DisplayName("notification API + WhatsApp RestClient beans: pooled, with their properties' timeouts")
    void notificationClients() {
        InnbucksNotifyProperties notify = new InnbucksNotifyProperties();
        notify.setBaseUrl("http://notify");
        notify.setConnectTimeoutMs(3400);
        notify.setReadTimeoutMs(20400);
        assertPooled(factoryOf(new InnbucksNotifyClientConfig().innbucksNotifyRestClient(notify, POOL)), 3400, 20400);

        WhatsAppProperties wa = new WhatsAppProperties();
        wa.setBaseUrl("http://wa");
        wa.setConnectTimeoutMs(2500);
        wa.setReadTimeoutMs(10500);
        assertPooled(factoryOf(new WhatsAppClientConfig().whatsAppRestClient(wa, POOL)), 2500, 10500);
    }

    @Test
    @DisplayName("the property defaults: shipped timeouts the clients above keep are unchanged")
    void shippedDefaultsAreKept() {
        HttpClientConfig http = new HttpClientConfig();
        assertThat(http.getConnectTimeout()).isEqualTo(10_000);
        assertThat(http.getReadTimeout()).isEqualTo(60_000);
        InnbucksNotifyProperties notify = new InnbucksNotifyProperties();
        assertThat(notify.getConnectTimeoutMs()).isEqualTo(3000);
        assertThat(notify.getReadTimeoutMs()).isEqualTo(20000);
        WhatsAppProperties wa = new WhatsAppProperties();
        assertThat(wa.getConnectTimeoutMs()).isEqualTo(2000);
        assertThat(wa.getReadTimeoutMs()).isEqualTo(10000);
    }

    private static ClientHttpRequestFactory factoryOf(RestClient rc) {
        return (ClientHttpRequestFactory) ReflectionTestUtils.getField(rc, "clientRequestFactory");
    }

    private static void assertPooled(ClientHttpRequestFactory f, long connectMs, long readMs) {
        assertThat(f).isInstanceOf(OutboundHttp.PooledRequestFactory.class);
        OutboundHttp.PooledRequestFactory pooled = (OutboundHttp.PooledRequestFactory) f;
        assertThat(pooled.getHttpClient()).as("the shared pool's client").isSameAs(POOL.httpClient());
        assertThat(pooled.connectTimeout()).isEqualTo(Duration.ofMillis(connectMs));
        assertThat(pooled.responseTimeout()).isEqualTo(Duration.ofMillis(readMs));
        var rcfg = pooled.effectiveRequestConfig();
        assertThat(OutboundHttpTest.connectTimeoutOf(rcfg)).isEqualTo(connectMs);
        assertThat(rcfg.getResponseTimeout().toMilliseconds()).isEqualTo(readMs);
        assertThat(rcfg.getConnectionRequestTimeout().toMilliseconds()).isEqualTo(Math.min(2000, connectMs));
    }
}
