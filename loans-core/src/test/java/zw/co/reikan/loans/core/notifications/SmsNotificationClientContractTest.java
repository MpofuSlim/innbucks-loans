package zw.co.reikan.loans.core.notifications;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test: pins {@link SmsNotificationClient}'s behaviour against each
 * response shape the InnBucks core gateway adapter can return, so a change in
 * the adapter's wire contract fails the build at PR time.
 *
 * <p>Pure JUnit + WireMock, no Spring context — the client's {@code RestClient}
 * is wired the same way {@link InnbucksGatewayClientConfig} builds it, just
 * pointed at the WireMock port.
 */
class SmsNotificationClientContractTest {

    private static WireMockServer wireMock;
    private static SmsNotificationClient client;

    @BeforeAll
    static void startWireMockAndWireClient() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        InnbucksGatewayProperties props = new InnbucksGatewayProperties();
        props.setBaseUrl("http://localhost:" + wireMock.port());
        props.setConnectTimeoutMs(500);
        props.setReadTimeoutMs(2000);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));
        RestClient restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
        client = new SmsNotificationClient(restClient);
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @AfterEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("happy path: adapter returns 200 → client returns normally and posts the documented body")
    void sendSms_happyPath_doesNotThrow() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r1\",\"status\":\"SUBMITTED\"}")));

        client.sendSms("+263782606983", "InnBucks temp password 123456", "r1");

        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/sms"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(matchingJsonPath("$.destination", equalTo("+263782606983")))
                .withRequestBody(matchingJsonPath("$.message", equalTo("InnBucks temp password 123456")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("r1")))
                .withRequestBody(matchingJsonPath("$.senderId", equalTo("INNBUCKS"))));
    }

    @Test
    @DisplayName("adapter returns 502: throws NotificationDeliveryException with status")
    void sendSms_adapterReturns502_throwsWithRejectionDetail() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse()
                        .withStatus(502)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r2\",\"status\":\"FAILED\",\"error\":\"messenger rejected\"}")));

        assertThatThrownBy(() -> client.sendSms("+263782606983", "msg", "r2"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 502");
    }

    @Test
    @DisplayName("adapter returns 503: throws NotificationDeliveryException")
    void sendSms_adapterReturns503_throwsAsRejection() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"reference\":\"r3\",\"status\":\"FAILED\",\"error\":\"unreachable\"}")));

        assertThatThrownBy(() -> client.sendSms("+263782606983", "msg", "r3"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    @DisplayName("adapter unreachable (connection refused): throws NotificationDeliveryException")
    void sendSms_adapterUnreachable_throwsWithUnreachableMessage() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient deadClient = RestClient.builder()
                .baseUrl("http://localhost:" + closedPort)
                .requestFactory(factory)
                .build();
        SmsNotificationClient unreachableClient = new SmsNotificationClient(deadClient);

        assertThatThrownBy(() -> unreachableClient.sendSms("+263782606983", "msg", "r4"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("blank destination is rejected client-side; no HTTP call is made")
    void sendSms_blankDestination_rejectedBeforeNetwork() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse().withStatus(200)));

        assertThatThrownBy(() -> client.sendSms("", "msg", "r5"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("recipient is blank");

        wireMock.verify(0, postRequestedFor(urlEqualTo("/notifications/sms")));
    }

    @Test
    @DisplayName("blank message is rejected client-side; no HTTP call is made")
    void sendSms_blankMessage_rejectedBeforeNetwork() {
        assertThatThrownBy(() -> client.sendSms("+263782606983", "  ", "r6"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("message is blank");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/notifications/sms")));
    }

    @Test
    @DisplayName("null reference is replaced with a LOANS-SMS-<uuid> reference on the wire")
    void sendSms_nullReference_isAutoGenerated() {
        wireMock.stubFor(post(urlEqualTo("/notifications/sms"))
                .willReturn(aResponse().withStatus(200)));

        client.sendSms("+263782606983", "msg", null);

        wireMock.verify(postRequestedFor(urlEqualTo("/notifications/sms"))
                .withRequestBody(matchingJsonPath("$.reference",
                        matching("LOANS-SMS-[0-9a-f-]{36}"))));
    }
}
