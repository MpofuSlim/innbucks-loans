package zw.co.innbucks.loans.core.notifications;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for {@link WhatsAppNotificationClient}, the fallback when an SMS fails: the ticketing fleet's
 * {@code POST /api/messages/custom-notification} wire shape ({@code {to, notification}}, lowercase {@code x-api-key}),
 * each response shape, a refused connection, and the guards that keep a call off the network.
 *
 * <p>Pure JUnit + WireMock, no Spring context.
 */
class WhatsAppNotificationClientContractTest {

    private static final String API_KEY = "wa-test-key";
    private static final String PATH = "/api/messages/custom-notification";

    private static WireMockServer wireMock;
    private WhatsAppNotificationClient client;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void setup() {
        wireMock.resetAll();
        client = client("http://localhost:" + wireMock.port(), API_KEY);
    }

    private static WhatsAppNotificationClient client(String baseUrl, String apiKey) {
        WhatsAppProperties properties = new WhatsAppProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey(apiKey);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(2000));
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl == null ? "" : baseUrl).requestFactory(factory).build();
        return new WhatsAppNotificationClient(restClient, properties);
    }

    @Test
    @DisplayName("happy path: posts {to, notification} with the x-api-key header, text unchanged")
    void sends() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

        client.sendCustomNotification("+263782606983", "Your offer: USD 300.00 — log in!");

        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("x-api-key", equalTo(API_KEY))
                .withRequestBody(equalToJson("{\"to\":\"+263782606983\","
                        + "\"notification\":\"Your offer: USD 300.00 — log in!\"}"))
                // A partner never receives our trace (CLAUDE.md "Tracing"): partner WAFs have
                // refused headers they did not expect.
                .withoutHeader("traceparent")
                .withoutHeader("tracestate"));
    }

    @Test
    @DisplayName("gateway 4xx: NotificationDeliveryException with the status")
    void rejected() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid number\"}")));

        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 400");
    }

    @Test
    @DisplayName("gateway unreachable (connection refused): NotificationDeliveryException")
    void unreachable() throws Exception {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        WhatsAppNotificationClient dead = client("http://localhost:" + closedPort, API_KEY);

        assertThatThrownBy(() -> dead.sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("no gateway URL or key configured: refused before the network, saying what to set")
    void unconfigured() {
        assertThatThrownBy(() -> client(null, API_KEY).sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("WHATSAPP_GATEWAY_URL");
        assertThatThrownBy(() -> client("http://localhost:" + wireMock.port(), " ")
                .sendCustomNotification("+263782606983", "msg"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("not configured");
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("blank recipient or message, or one over 1600 characters: refused before the network")
    void guards() {
        assertThatThrownBy(() -> client.sendCustomNotification(" ", "msg"))
                .hasMessageContaining("recipient is blank");
        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", ""))
                .hasMessageContaining("message is blank");
        assertThatThrownBy(() -> client.sendCustomNotification("+263782606983", "x".repeat(1601)))
                .hasMessageContaining("1600");
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }
}
