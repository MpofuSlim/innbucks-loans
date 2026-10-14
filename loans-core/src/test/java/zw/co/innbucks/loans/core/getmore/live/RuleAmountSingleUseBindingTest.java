package zw.co.innbucks.loans.core.getmore.live;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponSpec;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException.Reason;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;
import zw.co.innbucks.loans.core.getmore.GetMoreStore;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The LIVE amount models. Each refusal is proved where it matters: in the order find-or-create uses them (the rule
 * read, the precheck, THEN the generate), nothing is generated, so no coupon exists that the store would value at
 * whatever its rule says.
 */
class RuleAmountSingleUseBindingTest {

    private static final BigDecimal FACE = new BigDecimal("150.00");
    private static final LocalDate LAST_DAY = LocalDate.of(2026, 11, 13);
    private static final String PREFIX = "INN-K7Q2M3XW-";

    private GetMoreStore store;
    private final GetMoreAmountBinding binding = new RuleAmountSingleUseBinding("USD");

    @BeforeEach
    void storeThatWouldMint() {
        store = mock(GetMoreStore.class);
    }

    /** A rule fit for a USD 150.00 single-use voucher. */
    private static GetMoreSalesRule rule(boolean active, String simpleAction, int usesPerCoupon, String amount,
                                         LocalDate toDate) {
        return new GetMoreSalesRule(43, "InnBucks Staff Grocery", active, LocalDate.of(2026, 10, 1), toDate,
                simpleAction, new BigDecimal(amount), "SPECIFIC_COUPON", true, usesPerCoupon, 0, true, false);
    }

    private static GetMoreSalesRule fit() {
        return rule(true, "cart_fixed", 1, "150.0000", LocalDate.of(2027, 9, 30));
    }

    /** What find-or-create does: the precheck runs before the one write that can mint. */
    private void attempt(GetMoreAmountBinding binding, GetMoreSalesRule rule, String currency) {
        binding.precheck(rule, FACE, currency, LAST_DAY);
        store.generateCoupons(GetMoreCouponSpec.single(rule.ruleId(), PREFIX, 16));
    }

    private void assertRefused(GetMoreSalesRule rule, String currency, Reason reason, String message) {
        assertThatThrownBy(() -> attempt(binding, rule, currency))
                .isInstanceOfSatisfying(GetMoreRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e.httpStatus()).as("refused by loans, not the store").isZero();
                })
                .hasMessageContaining(message);
        verify(store, never()).generateCoupons(any());
    }

    @Test
    @DisplayName("a fit rule passes: active, auto-generating, cart_fixed, one use, the face value, long enough, USD")
    void fitRulePasses() {
        attempt(binding, fit(), "USD");
        verify(store).generateCoupons(any());
        assertThat(binding.partialSpending()).isFalse();
        assertThat(binding.name()).isEqualTo("RULE_AMOUNT_SINGLE_USE");
        assertThatCode(() -> binding.bind(null, FACE, "USD")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an inactive rule is refused before any generate")
    void inactive() {
        assertRefused(rule(false, "cart_fixed", 1, "150", null), "USD", Reason.RULE_NOT_USABLE, "not active");
    }

    @Test
    @DisplayName("a rule that does not auto-generate coupons is refused")
    void notAutoGenerating() {
        GetMoreSalesRule specific = new GetMoreSalesRule(43, "x", true, null, null, "cart_fixed",
                new BigDecimal("150"), "SPECIFIC_COUPON", false, 1, 0, true, false);
        assertRefused(specific, "USD", Reason.RULE_NOT_USABLE, "does not generate");

        GetMoreSalesRule auto = new GetMoreSalesRule(43, "x", true, null, null, "cart_fixed",
                new BigDecimal("150"), "AUTO", false, 1, 0, true, false);
        assertThatCode(() -> binding.precheck(auto, FACE, "USD", LAST_DAY)).as("coupon_type AUTO is enough")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a rule that is not cart_fixed is refused")
    void notCartFixed() {
        assertRefused(rule(true, "by_percent", 1, "150", null), "USD", Reason.RULE_NOT_USABLE, "not cart_fixed");
    }

    @Test
    @DisplayName("uses_per_coupon other than 1 is refused: a cart_fixed coupon gives its full value on every use")
    void usesPerCouponNotOne() {
        assertRefused(rule(true, "cart_fixed", 0, "150", null), "USD", Reason.RULE_NOT_USABLE, "0 uses per coupon");
        assertRefused(rule(true, "cart_fixed", 2, "150", null), "USD", Reason.RULE_NOT_USABLE, "2 uses per coupon");
    }

    @Test
    @DisplayName("a rule worth another amount is refused: 50.00 for a 5000.00 voucher; 150.0000 equals 150.00")
    void amountMismatch() {
        GetMoreSalesRule fifty = rule(true, "cart_fixed", 1, "50.00", null);
        assertThatThrownBy(() -> binding.precheck(fifty, new BigDecimal("5000.00"), "USD", LAST_DAY))
                .isInstanceOfSatisfying(GetMoreRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.VALUE_UNSUPPORTED));
        assertRefused(rule(true, "cart_fixed", 1, "149.99", null), "USD", Reason.VALUE_UNSUPPORTED, "149.99");
        assertThatCode(() -> binding.precheck(rule(true, "cart_fixed", 1, "150", null), FACE, "USD", LAST_DAY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a voucher in another currency is refused")
    void currency() {
        assertRefused(fit(), "ZWG", Reason.CURRENCY_UNSUPPORTED, "ZWG");
        assertThatCode(() -> binding.precheck(fit(), FACE, "usd", LAST_DAY)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a rule ending before the voucher's last day is refused; ending on it, or never, is fine")
    void toDateBeforeLastDay() {
        assertRefused(rule(true, "cart_fixed", 1, "150", LAST_DAY.minusDays(1)), "USD", Reason.RULE_NOT_USABLE,
                "before the voucher's last day");
        assertThatCode(() -> binding.precheck(rule(true, "cart_fixed", 1, "150", LAST_DAY), FACE, "USD", LAST_DAY))
                .doesNotThrowAnyException();
        assertThatCode(() -> binding.precheck(rule(true, "cart_fixed", 1, "150", null), FACE, "USD", LAST_DAY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NoLiveAmountModel (the default) always refuses, whatever the rule, before any generate")
    void noLiveAmountModelAlwaysRefuses() {
        GetMoreAmountBinding none = new NoLiveAmountModel();

        assertThatThrownBy(() -> attempt(none, fit(), "USD"))
                .isInstanceOfSatisfying(GetMoreRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.AMOUNT_MODEL_UNSET))
                .hasMessageContaining("GETMORE_LIVE_AMOUNT_MODEL is NONE");
        verify(store, never()).generateCoupons(any());
        assertThat(none.partialSpending()).isFalse();
        assertThat(none.name()).isEqualTo("NONE");
    }
}
