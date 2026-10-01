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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test: pins {@link SmsNotificationClient}'s behaviour against each
 * response shape the InnBucks notification API can return, so a change in its
 * wire contract fails the build at PR time. The wire shape is the ticketing
 * fleet's (user-service {@code SmsNotificationClientContractTest}), which sends
 * through the same API.
 *
 * <p>The client logs in via {@link NotificationApiAuthenticator}
 * ({@code POST /auth/third-party}) and presents {@code X-Api-Key} + a bearer
 * token on {@code POST /api/notification/sms}, body
 * {@code {message, reference, destinationMsisdn}}.
 *
 * <p>Pure JUnit + WireMock, no Spring context.
 */
class SmsNotificationClientContractTest {

    private static final String API_KEY = "test-api-key";
    private static final String LOGIN = "/auth/third-party";
    private static final String SMS = "/api/notification/sms";

    private static WireMockServer wireMock;
    private SmsNotificationClient client;

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
        // Login succeeds by default; individual tests add their own /api/notification/sms stub.
        wireMock.stubFor(post(urlEqualTo(LOGIN))
                .willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        client = smsClient(baseUrl(wireMock.port()), baseUrl(wireMock.port()));
    }

    private static String baseUrl(int port) {
        return "http://localhost:" + port;
    }

    /** Builds the client with the auth rail pointed at {@code authBaseUrl} and the SMS POST at {@code smsBaseUrl}. */
    private static SmsNotificationClient smsClient(String authBaseUrl, String smsBaseUrl) {
        InnbucksNotifyProperties props = new InnbucksNotifyProperties();
        props.setBaseUrl(authBaseUrl);
        props.setApiKey(API_KEY);
        props.setUsername("test-user");
        props.setPassword("test-pass");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(Duration.ofMillis(2000));

        RestClient authRestClient = RestClient.builder()
                .baseUrl(authBaseUrl).requestFactory(factory).build();
        RestClient gatewayRestClient = RestClient.builder()
                .baseUrl(smsBaseUrl).requestFactory(factory).build();
        NotificationApiAuthenticator authenticator = new NotificationApiAuthenticator(authRestClient, props);
        return new SmsNotificationClient(gatewayRestClient, authenticator);
    }

    @Test
    @DisplayName("happy path: logs in then posts the documented body with X-Api-Key + Bearer")
    void sendSms_happyPath_postsWithAuth() {
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"reference\":\"r1\",\"status\":\"SUBMITTED\"}")));

        client.sendSms("+263782606983", "InnBucks temp password 123456", "r1");

        wireMock.verify(postRequestedFor(urlEqualTo(LOGIN))
                .withHeader("X-Api-Key", equalTo(API_KEY)));
        wireMock.verify(postRequestedFor(urlEqualTo(SMS))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("Authorization", equalTo("Bearer tok-abc"))
                .withRequestBody(equalToJson("{\"message\":\"InnBucks temp password 123456\",\"reference\":\"r1\","
                        + "\"destinationMsisdn\":\"+263782606983\"}")));
    }

    @Test
    @DisplayName("every stored form of a Zimbabwean mobile number goes out in E.164")
    void sendSms_normalisesTheNumber() {
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(200)));

        client.sendSms("263782606983", "msg", "r-a");
        client.sendSms("0782606983", "msg", "r-b");

        wireMock.verify(2, postRequestedFor(urlEqualTo(SMS))
                .withRequestBody(matchingJsonPath("$.destinationMsisdn", equalTo("+263782606983"))));
    }

    @Test
    @DisplayName("the message is made safe for the API: the characters it refuses never reach it")
    void sendSms_sanitisesTheMessage() {
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(200)));

        client.sendSms("+263782606983", "Your loan ref: L-1/26 is approved! \u2014 Innbucks", "r-s");

        wireMock.verify(postRequestedFor(urlEqualTo(SMS))
                .withRequestBody(matchingJsonPath("$.message",
                        equalTo("Your loan ref L-1 26 is approved. - Innbucks"))));
    }

    @Test
    @DisplayName("API 401 once: refreshes the token and replays, then succeeds")
    void sendSms_gateway401_refreshesAndReplays() {
        wireMock.stubFor(post(urlEqualTo(SMS)).inScenario("auth")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        wireMock.stubFor(post(urlEqualTo(SMS)).inScenario("auth")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200)));

        client.sendSms("+263782606983", "msg", "r-401");

        // Two SMS attempts (401 then 200) and a second login for the forced refresh.
        wireMock.verify(2, postRequestedFor(urlEqualTo(SMS)));
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("API 401 twice: gives up with a credentials-rejected error")
    void sendSms_gateway401Twice_throws() {
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client.sendSms("+263782606983", "msg", "r-401b"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("API 502: throws NotificationDeliveryException with status")
    void sendSms_gateway502_throwsWithRejectionDetail() {
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse()
                .withStatus(502)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"reference\":\"r2\",\"status\":\"FAILED\",\"error\":\"messenger rejected\"}")));

        assertThatThrownBy(() -> client.sendSms("+263782606983", "msg", "r2"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("HTTP 502");
    }

    @Test
    @DisplayName("API unreachable (connection refused): throws NotificationDeliveryException")
    void sendSms_gatewayUnreachable_throwsWithUnreachableMessage() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        // Auth succeeds against WireMock; the SMS POST targets a dead port.
        SmsNotificationClient unreachableClient = smsClient(baseUrl(wireMock.port()), baseUrl(closedPort));

        assertThatThrownBy(() -> unreachableClient.sendSms("+263782606983", "msg", "r4"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    @DisplayName("no notification API credentials: refused before the network")
    void sendSms_unconfigured_rejectedBeforeNetwork() {
        InnbucksNotifyProperties blank = new InnbucksNotifyProperties();
        RestClient restClient = RestClient.builder().baseUrl(baseUrl(wireMock.port())).build();
        SmsNotificationClient unconfigured = new SmsNotificationClient(restClient,
                new NotificationApiAuthenticator(restClient, blank));

        assertThatThrownBy(() -> unconfigured.sendSms("+263782606983", "msg", "r-u"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("not configured");
        wireMock.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(SMS)));
    }

    @Test
    @DisplayName("blank destination is rejected client-side; no HTTP call is made")
    void sendSms_blankDestination_rejectedBeforeNetwork() {
        assertThatThrownBy(() -> client.sendSms("", "msg", "r5"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("recipient is blank");

        wireMock.verify(0, postRequestedFor(urlEqualTo(SMS)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("blank message is rejected client-side; no HTTP call is made")
    void sendSms_blankMessage_rejectedBeforeNetwork() {
        assertThatThrownBy(() -> client.sendSms("+263782606983", "  ", "r6"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("message is blank");
        wireMock.verify(0, postRequestedFor(urlEqualTo(SMS)));
    }

    @Test
    @DisplayName("null reference is replaced with a LOANS-SMS-<uuid> reference on the wire")
    void sendSms_nullReference_isAutoGenerated() {
        wireMock.stubFor(post(urlEqualTo(SMS)).willReturn(aResponse().withStatus(200)));

        client.sendSms("+263782606983", "msg", null);

        wireMock.verify(postRequestedFor(urlEqualTo(SMS))
                .withRequestBody(matchingJsonPath("$.reference",
                        matching("LOANS-SMS-[0-9a-f-]{36}"))));
    }
}
