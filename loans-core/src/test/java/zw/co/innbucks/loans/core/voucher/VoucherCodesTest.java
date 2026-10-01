package zw.co.innbucks.loans.core.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The voucher code's shape (FR-SGL-033): digits, a Damm check digit, groups of four, and how it is written. */
class VoucherCodesTest {

    private static final String CODE = "4829150673318406";

    @Test
    @DisplayName("the Damm check digit matches the published example (572 -> 5724)")
    void dammPublishedExample() {
        assertThat(Damm.checkDigit("572")).isEqualTo('4');
        assertThat(Damm.isValid("5724")).isTrue();
        assertThat(Damm.isValid("5723")).isFalse();
    }

    @Test
    @DisplayName("every single wrong digit and every swap of two neighbouring digits is caught")
    void dammCatchesTyposAndSwaps() {
        assertThat(Damm.isValid(CODE)).isTrue();
        for (int i = 0; i < CODE.length(); i++) {
            for (char digit = '0'; digit <= '9'; digit++) {
                if (digit != CODE.charAt(i)) {
                    String typo = CODE.substring(0, i) + digit + CODE.substring(i + 1);
                    assertThat(Damm.isValid(typo)).as(typo).isFalse();
                }
            }
        }
        for (int i = 0; i + 1 < CODE.length(); i++) {
            if (CODE.charAt(i) != CODE.charAt(i + 1)) {
                String swapped = CODE.substring(0, i) + CODE.charAt(i + 1) + CODE.charAt(i) + CODE.substring(i + 2);
                assertThat(Damm.isValid(swapped)).as(swapped).isFalse();
            }
        }
    }

    @Test
    @DisplayName("a new code is 16 digits, never starts with 0, carries a valid check digit, and is not repeated")
    void generatedCodes() {
        VoucherCodeGenerator generator = new VoucherCodeGenerator(new Random(42));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = generator.next(16);
            assertThat(code).hasSize(16).matches("[1-9][0-9]{15}");
            assertThat(Damm.isValid(code)).isTrue();
            assertThat(seen.add(code)).as("unique").isTrue();
        }
        assertThat(generator.next(24)).hasSize(24);
        assertThat(generator.next(12)).hasSize(12);
        assertThatThrownBy(() -> generator.next(14)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator.next(28)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a code is accepted as keyed or scanned: spaced, dashed or bare")
    void normalizeAcceptsEveryWrittenForm() {
        assertThat(VoucherCodes.normalize(CODE)).contains(CODE);
        assertThat(VoucherCodes.normalize("4829 1506 7331 8406")).contains(CODE);
        assertThat(VoucherCodes.normalize("4829-1506-7331-8406")).contains(CODE);
        assertThat(VoucherCodes.normalize(" 4829 1506-7331 8406 ")).contains(CODE);
    }

    @Test
    @DisplayName("anything that cannot be a code is refused without a lookup")
    void normalizeRefusesWhatCannotBeACode() {
        assertThat(VoucherCodes.normalize(null)).isEmpty();
        assertThat(VoucherCodes.normalize("")).isEmpty();
        assertThat(VoucherCodes.normalize("4829150673318407")).as("wrong check digit").isEmpty();
        assertThat(VoucherCodes.normalize("4829150673318406A")).as("a letter").isEmpty();
        assertThat(VoucherCodes.normalize("4829.1506.7331.8406")).as("dots").isEmpty();
        assertThat(VoucherCodes.normalize("57247")).as("too short").isEmpty();
        assertThat(VoucherCodes.normalize("1".repeat(25))).as("too long").isEmpty();
    }

    @Test
    @DisplayName("on screen in spaced groups, in a message in dashed groups, masked to the last group elsewhere")
    void writtenForms() {
        assertThat(VoucherCodes.display(CODE)).isEqualTo("4829 1506 7331 8406");
        assertThat(VoucherCodes.forMessage(CODE)).isEqualTo("4829-1506-7331-8406");
        assertThat(VoucherCodes.scanValue(CODE)).isEqualTo(CODE);
        assertThat(VoucherCodes.masked(VoucherCodes.lastFour(CODE), CODE.length())).isEqualTo("**** **** **** 8406");
        assertThat(VoucherCodes.masked("2151", 12)).isEqualTo("**** **** 2151");
        assertThat(VoucherCodes.display("123456789012345678901234")).isEqualTo("1234 5678 9012 3456 7890 1234");
    }
}
