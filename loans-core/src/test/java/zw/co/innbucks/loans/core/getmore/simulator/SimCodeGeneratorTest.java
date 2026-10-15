package zw.co.innbucks.loans.core.getmore.simulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.getmore.CouponFormat;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The simulated store's codes: Magento's alphabets, the length asked for, never a predictable sequence. */
class SimCodeGeneratorTest {

    private final SimCodeGenerator generator = new SimCodeGenerator();

    @Test
    @DisplayName("alphanum, alpha and num draw from A-Z0-9, A-Z and 0-9, at exactly the length asked")
    void charsetsAndLength() {
        for (int i = 0; i < 200; i++) {
            assertThat(generator.random(16, CouponFormat.ALPHANUM)).matches("[A-Z0-9]{16}");
            assertThat(generator.random(12, CouponFormat.ALPHA)).matches("[A-Z]{12}");
            assertThat(generator.random(9, CouponFormat.NUM)).matches("[0-9]{9}");
        }
        assertThat(generator.random(1, CouponFormat.NUM)).hasSize(1);
        assertThat(generator.random(32, CouponFormat.ALPHANUM)).hasSize(32);
    }

    @Test
    @DisplayName("a code is the prefix then the random characters: of our shape, typed back as written")
    void codeCarriesThePrefix() {
        String prefix = GetMoreCodes.prefixFor("SIM-", GetMoreCodes.newNonce());

        String code = generator.code(prefix, 16, CouponFormat.ALPHANUM);

        assertThat(code).startsWith(prefix).hasSize(prefix.length() + 16);
        assertThat(GetMoreCodes.normalize(code)).contains(code);
        assertThat(GetMoreCodes.maskAll("code " + code, List.of())).doesNotContain(code);
        assertThat(generator.code(null, 8, CouponFormat.NUM)).matches("[0-9]{8}");
    }

    @Test
    @DisplayName("10 000 draws of 16 alphanumerics never repeat, and every character of the alphabet appears")
    void uniqueAndSpread() {
        Set<String> seen = new HashSet<>();
        Set<Character> characters = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String random = generator.random(16, CouponFormat.ALPHANUM);
            assertThat(seen.add(random)).as("draw %d repeated", i).isTrue();
            random.chars().forEach(c -> characters.add((char) c));
        }
        assertThat(characters).hasSize(36);
    }

    @Test
    @DisplayName("a length below 1 is refused")
    void lengthRefused() {
        assertThatThrownBy(() -> generator.random(0, CouponFormat.ALPHANUM))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
