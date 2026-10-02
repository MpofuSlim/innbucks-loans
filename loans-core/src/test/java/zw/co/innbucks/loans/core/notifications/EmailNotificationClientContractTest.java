package zw.co.innbucks.loans.core.notifications;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test for {@link EmailNotificationClient} against the InnBucks public
 * notification API: {@code POST /auth/third-party} for the bearer then
 * {@code POST /api/notification/email}. Pins the OUTBOUND auth headers, the body
 * (the branded HTML by default, signed plain text with HTML off), the ASCII-safe
 * subject, the 401-refresh-and-replay behaviour, SMTP first with this API as its
 * fallback, and that a credential email's upstream reply is never logged. A fresh
 * client per test keeps the token cache empty.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailNotificationClientContractTest {

    private static final String LOGIN = "/auth/third-party";
    private static final String EMAIL = "/api/notification/email";
    private static final String API_KEY = "test-api-key";

    private static WireMockServer wireMock;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @AfterEach
    void reset() {
        wireMock.resetAll();
    }

    private static EmailNotificationClient client(int port) {
        return client(port, false, null);
    }

    private static EmailNotificationClient client(int port, boolean html, SmtpEmailSender smtp) {
        InnbucksNotifyProperties props = new InnbucksNotifyProperties();
        props.setHtmlEnabled(html);
        props.setBaseUrl("http://localhost:" + port);
        props.setApiKey(API_KEY);
        props.setUsername("test-user");
        props.setPassword("test-pass");
        // HTTP/1.1 via SimpleClientHttpRequestFactory, matching the prod
        // InnbucksNotifyClientConfig — the default RestClient factory negotiates
        // HTTP/2, which WireMock answers with RST_STREAM.
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        NotificationApiAuthenticator authenticator = new NotificationApiAuthenticator(restClient, props);
        return new EmailNotificationClient(restClient, authenticator, props, smtp);
    }

    @Test
    @DisplayName("HTML off: logs in then posts {subject,message,reference,destinationEmail}, the text signed, with"
            + " X-Api-Key + Bearer")
    void sendEmail_postsDocumentedShape() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(200)));

        client(wireMock.port()).sendEmail("agent@example.com",
                "Your Innbucks Loans account is ready",
                "Temporary password: abc123", "AGENT-ONBOARD-1");

        wireMock.verify(postRequestedFor(urlEqualTo(LOGIN))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("Accept", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.username", equalTo("test-user")))
                .withRequestBody(matchingJsonPath("$.password", equalTo("test-pass"))));
        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withHeader("X-Api-Key", equalTo(API_KEY))
                .withHeader("Authorization", equalTo("Bearer tok-abc"))
                .withHeader("Accept", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Your Innbucks Loans account is ready")))
                .withRequestBody(matchingJsonPath("$.message",
                        equalTo(EmailSignature.appendTo("Temporary password: abc123"))))
                .withRequestBody(matchingJsonPath("$.destinationEmail", equalTo("agent@example.com")))
                .withRequestBody(matchingJsonPath("$.reference", equalTo("AGENT-ONBOARD-1"))));
    }

    @Test
    @DisplayName("HTML on (the default): the branded email with its button; the subject made ASCII-safe")
    void html_isTheBrandedEmail() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(200)));
        BrandedEmailRenderer.CallToAction button = new BrandedEmailRenderer.CallToAction(
                "Sign in to InnBucks Lending", "https://dtx-staging.innbucks.co.zw/lending/");

        client(wireMock.port(), true, null).sendEmail("tawanda@innbucks.co.zw", "Overdue: credit decision – loan 12",
                "Hi Tawanda,\n\nUsername: mpofuslim\nTemporary password: Kp7rQ-n4mTx", "R-1", button, true);

        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withRequestBody(matchingJsonPath("$.subject", equalTo("Overdue credit decision - loan 12")))
                .withRequestBody(matchingJsonPath("$.message", equalTo(BrandedEmailRenderer.render(
                        "Overdue: credit decision – loan 12",
                        "Hi Tawanda,\n\nUsername: mpofuslim\nTemporary password: Kp7rQ-n4mTx", null, button))))
                .withRequestBody(matchingJsonPath("$.message", containing(
                        "href=\"https://dtx-staging.innbucks.co.zw/lending/\"")))
                .withRequestBody(matchingJsonPath("$.message", containing("The InnBucks Lending Team"))));
    }

    @Test
    @DisplayName("SMTP on: the email goes over SMTP, with the same body, and the notification API is not called")
    void smtpFirst() {
        SmtpEmailSender smtp = mock(SmtpEmailSender.class);
        when(smtp.isEnabled()).thenReturn(true);

        client(wireMock.port(), true, smtp).sendEmail("hc1@innbucks.co.zw", "Staff Grocery Loan SGL-2026-000151",
                "A line", null);

        verify(smtp).send("hc1@innbucks.co.zw", "Staff Grocery Loan SGL-2026-000151",
                BrandedEmailRenderer.render("Staff Grocery Loan SGL-2026-000151", "A line", null, null), true);
        wireMock.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(EMAIL)));
    }

    @Test
    @DisplayName("SMTP fails: the notification API takes the same email; SMTP off: it is never tried")
    void smtpFallsBackToTheApi() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(200)));
        SmtpEmailSender smtp = mock(SmtpEmailSender.class);
        when(smtp.isEnabled()).thenReturn(true);
        doThrow(new NotificationDeliveryException("SMTP delivery failed: 535 Authentication Credentials Invalid"))
                .when(smtp).send(anyString(), anyString(), anyString(), anyBoolean());

        client(wireMock.port(), false, smtp).sendEmail("hc1@innbucks.co.zw", "s", "m", "R-2");

        wireMock.verify(1, postRequestedFor(urlEqualTo(EMAIL))
                .withRequestBody(matchingJsonPath("$.message", equalTo(EmailSignature.appendTo("m")))));

        SmtpEmailSender off = mock(SmtpEmailSender.class);
        client(wireMock.port(), false, off).sendEmail("hc1@innbucks.co.zw", "s", "m", "R-3");
        verify(off, never()).send(anyString(), anyString(), anyString(), anyBoolean());
        wireMock.verify(2, postRequestedFor(urlEqualTo(EMAIL)));
    }

    @Test
    @DisplayName("a credential email: a refusal's body is never logged, whatever it quotes back; others are")
    void withheld_neverLogsTheUpstreamReply(CapturedOutput output) {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(400)
                .withBody("{\"errors\":[\"Invalid message: Temporary password Kp7rQ-n4mTx\"]}")));
        SmtpEmailSender smtp = mock(SmtpEmailSender.class);
        when(smtp.isEnabled()).thenReturn(true);
        doThrow(new NotificationDeliveryException("SMTP delivery failed: echoed Kp7rQ-n4mTx"))
                .when(smtp).send(anyString(), anyString(), anyString(), anyBoolean());

        assertThatThrownBy(() -> client(wireMock.port(), true, smtp).sendEmail("t@innbucks.co.zw", "s",
                "Temporary password: Kp7rQ-n4mTx", "R-4", null, true))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageNotContaining("Kp7rQ-n4mTx");
        assertThat(output.getOut()).doesNotContain("Kp7rQ-n4mTx").contains("body=<withheld>");

        assertThatThrownBy(() -> client(wireMock.port()).sendEmail("t@innbucks.co.zw", "s", "m", "R-5"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThat(output.getOut()).contains("Invalid message");
    }

    @Test
    @DisplayName("blank recipient/subject/message: guarded before any network call")
    void blankInputs_neverHitTheWire() {
        EmailNotificationClient client = client(wireMock.port());
        assertThatThrownBy(() -> client.sendEmail(" ", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendEmail("a@b.com", " ", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        assertThatThrownBy(() -> client.sendEmail("a@b.com", "s", " ", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
        wireMock.verify(0, postRequestedFor(urlEqualTo(LOGIN)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(EMAIL)));
    }

    @Test
    @DisplayName("blank reference: auto-fills LOANS-EMAIL-<uuid>")
    void blankReference_autoFilled() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(200)));

        client(wireMock.port()).sendEmail("agent@example.com", "subj", "msg", null);

        wireMock.verify(postRequestedFor(urlEqualTo(EMAIL))
                .withRequestBody(matchingJsonPath("$.reference", matching("LOANS-EMAIL-[0-9a-f-]{36}"))));
    }

    @Test
    @DisplayName("401 on email: refresh token and replay once, then succeed")
    void unauthorized_refreshesAndReplays() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).inScenario("auth")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("retried"));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).inScenario("auth")
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(200)));

        assertThatCode(() -> client(wireMock.port()).sendEmail("a@b.com", "s", "m", "r"))
                .doesNotThrowAnyException();

        wireMock.verify(2, postRequestedFor(urlEqualTo(EMAIL)));
        wireMock.verify(2, postRequestedFor(urlEqualTo(LOGIN)));
    }

    @Test
    @DisplayName("persistent 401: refresh once then give up with NotificationDeliveryException")
    void unauthorizedTwice_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client(wireMock.port()).sendEmail("a@b.com", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("login rejected (401): NotificationDeliveryException, email never attempted")
    void loginRejected_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> client(wireMock.port()).sendEmail("a@b.com", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("login failed");
        wireMock.verify(0, postRequestedFor(urlEqualTo(EMAIL)));
    }

    @Test
    @DisplayName("500 on email (non-401): NotificationDeliveryException")
    void serverError_throws() {
        wireMock.stubFor(post(urlEqualTo(LOGIN)).willReturn(okJson("{\"accessToken\":\"tok-abc\"}")));
        wireMock.stubFor(post(urlEqualTo(EMAIL)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client(wireMock.port()).sendEmail("a@b.com", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("connect refused: NotificationDeliveryException (separate dead-port client)")
    void connectRefused_throws() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThatThrownBy(() -> client(closedPort).sendEmail("a@b.com", "s", "m", "r"))
                .isInstanceOf(NotificationDeliveryException.class);
    }
}
