package zw.co.innbucks.loans.core.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import zw.co.innbucks.loans.core.notifications.BrandedEmailRenderer;
import zw.co.innbucks.loans.core.notifications.SmsTextSanitizer;
import zw.co.innbucks.loans.core.user.PortalCredentialMessages.Reason;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The words a portal user gets with a temporary password: which system, which username, which password, and where to
 * sign in; and a temporary password that reaches them by SMS exactly as it was made.
 */
class PortalCredentialMessagesTest {

    private static final String PASSWORD = "Kp7rQ-n4mTx";

    private static PortalCredentialMessages messages(String signInUrl) {
        PortalProperties properties = new PortalProperties();
        properties.setSignInUrl(signInUrl);
        return new PortalCredentialMessages(properties);
    }

    @Test
    @DisplayName("SMS: one sentence each, naming the portal, the username and the password, with the bare sign-in"
            + " address")
    void smsWording() {
        PortalCredentialMessages messages = messages("https://lending.innbucks.co.zw");

        assertThat(messages.sms(Reason.ACCOUNT_CREATED, "Dionne", "dionne", PASSWORD)).isEqualTo("Hi Dionne, your"
                + " InnBucks Lending account is ready. Your username is dionne and your temporary password is"
                + " Kp7rQ-n4mTx. Please sign in at lending.innbucks.co.zw and change it immediately.");
        assertThat(messages.sms(Reason.ADMIN_RESET, "Tawanda", "mpofuslim", PASSWORD)).isEqualTo("Hi Tawanda, your"
                + " InnBucks Lending password has been reset by an administrator. Your username is mpofuslim and"
                + " your temporary password is Kp7rQ-n4mTx. Please sign in at lending.innbucks.co.zw and change it"
                + " immediately.");
        assertThat(messages.sms(Reason.SELF_SERVICE_RESET, " ", "mpofuslim", PASSWORD)).isEqualTo("Hello, your"
                + " InnBucks Lending password has been reset. Your username is mpofuslim and your temporary"
                + " password is Kp7rQ-n4mTx. Please sign in at lending.innbucks.co.zw and change it immediately. If you"
                + " did not ask for this, tell your administrator.");
    }

    @Test
    @DisplayName("every SMS passes the gateway unchanged, so the password and username arrive as they were made")
    void smsPassesTheGatewayUnchanged() {
        PortalCredentialMessages linked = messages("https://lending.innbucks.co.zw/");
        PortalCredentialMessages plain = messages("");
        for (int i = 0; i < 200; i++) {
            String password = TemporaryPasswordGenerator.generate();
            for (Reason reason : Reason.values()) {
                for (PortalCredentialMessages messages : new PortalCredentialMessages[]{linked, plain}) {
                    String sms = messages.sms(reason, "Tendai", "t.moyo@harare-motors", password);
                    assertThat(SmsTextSanitizer.toGsmSafe(sms)).isEqualTo(sms).contains(" " + password + ". ");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://dtx.innbucks.co.zw/foundry/lending", "https://lending.innbucks.co.zw:8443",
            "https://lending.innbucks.co.zw/?from=sms"})
    @DisplayName("an address SMS cannot carry (a path, a port, a query) is left out of the SMS; WhatsApp and email"
            + " still carry it whole")
    void addressSmsCannotCarry(String signInUrl) {
        PortalCredentialMessages messages = messages(signInUrl);

        assertThat(messages.sms(Reason.ACCOUNT_CREATED, "Dionne", "dionne", PASSWORD))
                .endsWith("Kp7rQ-n4mTx. Please sign in and change it immediately.");
        assertThat(messages.whatsApp(Reason.ACCOUNT_CREATED, "Dionne", "dionne", PASSWORD))
                .contains("Sign in at: " + signInUrl + "\n");
        assertThat(messages.email(Reason.ACCOUNT_CREATED, "Dionne", "dionne", PASSWORD))
                .contains("Sign in at: " + signInUrl + "\n");
    }

    @Test
    @DisplayName("no address configured: the message names the portal and has no sign-in line")
    void noAddress() {
        PortalCredentialMessages messages = messages("");

        assertThat(messages.sms(Reason.ACCOUNT_CREATED, "Dionne", "dionne", PASSWORD))
                .endsWith("Please sign in and change it immediately.");
        assertThat(messages.whatsApp(Reason.ACCOUNT_CREATED, "Dionne", "dionne", PASSWORD)).isEqualTo("Hi Dionne, your"
                + " InnBucks Lending account is ready.\n\nUsername: dionne\nTemporary password: Kp7rQ-n4mTx\n\nYou"
                + " will be asked to choose your own password when you sign in.");
    }

    @Test
    @DisplayName("an older username the SMS gateway would alter is left out of the SMS rather than sent wrong")
    void usernameSmsCannotCarry() {
        PortalCredentialMessages messages = messages("");

        assertThat(messages.sms(Reason.ADMIN_RESET, "Tendai", "t_moyo", PASSWORD)).isEqualTo("Hi Tendai, your InnBucks"
                + " Lending password has been reset by an administrator. Your temporary password is"
                + " Kp7rQ-n4mTx. Please sign in and change it immediately.");
        assertThat(messages.whatsApp(Reason.ADMIN_RESET, "Tendai", "t_moyo", PASSWORD)).contains("Username: t_moyo\n");
    }

    @Test
    @DisplayName("the email's sign-in button: an https address only; its sign-off comes with the branded footer")
    void signInButton() {
        assertThat(messages("https://dtx-staging.innbucks.co.zw/lending/").signInButton())
                .isEqualTo(new BrandedEmailRenderer.CallToAction("Sign in to InnBucks Lending",
                        "https://dtx-staging.innbucks.co.zw/lending/"));
        assertThat(messages("http://dtx-staging.innbucks.co.zw/lending/").signInButton()).isNull();
        assertThat(messages("").signInButton()).isNull();
        assertThat(messages("").email(Reason.ADMIN_RESET, "Tawanda", "mpofuslim", PASSWORD))
                .endsWith("you will be asked to choose your own password when you sign in.")
                .doesNotContain("Team");
    }

    @Test
    @DisplayName("email subjects name the portal in ASCII with no colon")
    void emailSubjects() {
        PortalCredentialMessages messages = messages("");
        for (Reason reason : Reason.values()) {
            assertThat(messages.emailSubject(reason)).contains("InnBucks Lending").doesNotContain(":")
                    .matches("\\p{ASCII}+");
        }
    }

    @Test
    @DisplayName("a temporary password is two groups of five from an alphabet with no look-alikes or symbols, fresh"
            + " every time")
    void temporaryPasswords() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String password = TemporaryPasswordGenerator.generate();
            assertThat(password).matches("[" + TemporaryPasswordGenerator.ALPHABET + "]{5}-["
                    + TemporaryPasswordGenerator.ALPHABET + "]{5}");
            assertThat(SmsTextSanitizer.toGsmSafe(password)).isEqualTo(password);
            seen.add(password);
        }
        assertThat(seen).hasSize(1000);
        assertThat(TemporaryPasswordGenerator.ALPHABET).doesNotContainAnyWhitespaces()
                .doesNotContain("0", "O", "o", "1", "l", "I");
    }
}
