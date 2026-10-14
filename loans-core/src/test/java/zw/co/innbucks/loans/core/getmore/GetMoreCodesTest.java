package zw.co.innbucks.loans.core.getmore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The typed form of a GetMore code, its per-issuance prefix, and the one masked form every surface uses. */
class GetMoreCodesTest {

    private static final String CODE = "INN-K7Q2M3XW-7KQ2M9XWD4RT8B1C";

    @Test
    @DisplayName("a nonce is 8 characters of RFC 4648 base32, freshly random")
    void newNonce() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String nonce = GetMoreCodes.newNonce();
            assertThat(nonce).matches("[A-Z2-7]{8}");
            seen.add(nonce);
        }
        assertThat(seen).hasSizeGreaterThan(495);
    }

    @Test
    @DisplayName("prefixFor joins the configured prefix, the nonce and a dash; hasPrefix ignores case")
    void prefixFor() {
        String prefix = GetMoreCodes.prefixFor("SIM-", "K7Q2M3XW");
        assertThat(prefix).isEqualTo("SIM-K7Q2M3XW-");
        assertThat(GetMoreCodes.hasPrefix("SIM-K7Q2M3XW-ABCDEFGH12345678", prefix)).isTrue();
        assertThat(GetMoreCodes.hasPrefix("sim-k7q2m3xw-abcdefgh12345678", prefix)).isTrue();
        assertThat(GetMoreCodes.hasPrefix("SIM-K7Q2M3XX-ABCDEFGH12345678", prefix)).isFalse();
        assertThat(GetMoreCodes.hasPrefix("SIM-", prefix)).isFalse();
        assertThat(GetMoreCodes.hasPrefix(null, prefix)).isFalse();
    }

    @Test
    @DisplayName("normalize: trimmed, spaces removed, upper-cased, then 8 to 64 of A-Z 0-9 -")
    void normalize() {
        assertThat(GetMoreCodes.normalize("  inn-k7q2m3xw-7kq2 m9xw d4rt 8b1c ")).contains(CODE);
        assertThat(GetMoreCodes.normalize("ABCD123")).isEmpty();
        assertThat(GetMoreCodes.normalize("ABCD_1234")).isEmpty();
        assertThat(GetMoreCodes.normalize("A".repeat(65))).isEmpty();
        assertThat(GetMoreCodes.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("lastFour and masked: ****8B1C is the only masked form")
    void maskedForm() {
        assertThat(GetMoreCodes.lastFour("inn-k7q2m3xw-7kq2m9xwd4rt8b1c")).isEqualTo("8B1C");
        assertThat(GetMoreCodes.masked("8B1C")).isEqualTo("****8B1C");
        assertThat(GetMoreCodes.maskedCode(CODE)).isEqualTo("****8B1C");
        assertThat(GetMoreCodes.maskedCode(null)).isNull();
    }

    @Test
    @DisplayName("maskAll masks a known code in any case, wherever it appears")
    void masksKnownCodeAnyCase() {
        String text = "Coupon " + CODE + " was used; again: " + CODE.toLowerCase() + ".";
        assertThat(GetMoreCodes.maskAll(text, List.of(CODE)))
                .isEqualTo("Coupon ****8B1C was used; again: ****8B1C.");
    }

    @Test
    @DisplayName("maskAll masks our code shape even when the code was not known to the call")
    void masksOurShape() {
        assertThat(GetMoreCodes.maskAll("Code SIM-ABCDEFGH-ZZZZYYYY1234XW99 is not valid", List.of()))
                .isEqualTo("Code ****XW99 is not valid");
    }

    @Test
    @DisplayName("maskAll masks a prefix-less 16-character code echoed in GetMore's text, and a lower-cased echo")
    void masksPrefixlessCodes() {
        assertThat(GetMoreCodes.maskAll("The coupon code \"7KQ2M9XWD4RT8B1C\" is not valid.", List.of()))
                .isEqualTo("The coupon code \"****8B1C\" is not valid.");
        assertThat(GetMoreCodes.maskAll("coupon 7kq2m9xwd4rt8b1c", null)).isEqualTo("coupon ****8B1C");
    }

    @Test
    @DisplayName("maskAll keeps a Magento report id, loan references and ISO timestamps")
    void keepsReportIdsReferencesAndTimestamps() {
        String text = "Internal Error. Details are available in Magento log file. Report ID: webapi-6527d9f8a1b2c "
                + "for loan SGL-2026-000151 at 2026-10-14T08:30:00Z (2026-10-14 08:30:00)";
        assertThat(GetMoreCodes.maskAll(text, List.of())).isEqualTo(text);
        assertThat(GetMoreCodes.maskAll("rule_id = 43, order 000000123456", List.of()))
                .as("digits only are not a code").isEqualTo("rule_id = 43, order 000000123456");
    }

    @Test
    @DisplayName("a deadline is checked before a call: it throws DEADLINE once passed, never with none()")
    void deadline() {
        Clock now = Clock.fixed(Instant.parse("2026-10-14T08:30:00Z"), ZoneOffset.UTC);
        Deadline tenSeconds = Deadline.after(now, Duration.ofSeconds(10));

        tenSeconds.check(now);
        Deadline.none().check(Clock.offset(now, Duration.ofDays(365)));
        assertThatThrownBy(() -> tenSeconds.check(Clock.offset(now, Duration.ofSeconds(10))))
                .isInstanceOfSatisfying(GetMoreUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(GetMoreUnavailableException.Reason.DEADLINE));
    }
}
