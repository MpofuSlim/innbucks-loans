package zw.co.innbucks.loans.core.getmore.simulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The simulated coupon's balance: allowance, less what every order not cancelled took (net of anything cancelled),
 * plus what refunded credit memos gave back. Held (not yet invoiced) sales count against it, as at a real till.
 */
class SimulatedBalanceTest {

    private static final BigDecimal ALLOWANCE = new BigDecimal("150.0000");

    private static SimOrder order(String state, String amount, String invoiced, String canceled) {
        return SimOrder.builder().state(state).discountAmount(new BigDecimal(amount))
                .discountInvoiced(new BigDecimal(invoiced)).discountRefunded(BigDecimal.ZERO)
                .discountCanceled(new BigDecimal(canceled)).build();
    }

    private static SimCreditMemo memo(int state, String amount) {
        return SimCreditMemo.builder().state(state).discountAmount(new BigDecimal(amount)).build();
    }

    @Test
    @DisplayName("nothing spent: the whole allowance")
    void untouched() {
        assertThat(SimulatedBalance.of(ALLOWANCE, List.of(), List.of())).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("spent in parts down to nothing: 45.50 + 54.50 + 50.00 leaves 0")
    void spentInParts() {
        List<SimOrder> orders = List.of(order("complete", "-45.5000", "-45.5000", "0"),
                order("complete", "-54.5000", "-54.5000", "0"), order("complete", "-50.0000", "-50.0000", "0"));

        assertThat(SimulatedBalance.of(ALLOWANCE, orders, List.of())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a held sale (ordered, not yet invoiced) counts against the balance")
    void heldCounts() {
        List<SimOrder> orders = List.of(order("processing", "-20.0000", "0", "0"));

        assertThat(SimulatedBalance.of(ALLOWANCE, orders, List.of())).isEqualByComparingTo("130.00");
    }

    @Test
    @DisplayName("a refunded memo gives its amount back; open and cancelled memos give nothing")
    void refundsRestore() {
        List<SimOrder> orders = List.of(order("complete", "-45.5000", "-45.5000", "0"),
                order("complete", "-54.5000", "-54.5000", "0"));
        List<SimCreditMemo> memos = List.of(memo(SimCreditMemo.REFUNDED, "-10.0000"),
                memo(SimCreditMemo.OPEN, "-5.0000"), memo(SimCreditMemo.CANCELED, "-7.0000"));

        assertThat(SimulatedBalance.of(ALLOWANCE, orders, memos)).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("a cancelled order takes nothing; a partly cancelled one takes what was not cancelled")
    void cancellations() {
        List<SimOrder> orders = List.of(order("canceled", "-30.0000", "0", "30.0000"),
                order("processing", "-40.0000", "0", "15.0000"));

        assertThat(SimulatedBalance.of(ALLOWANCE, orders, List.of())).isEqualByComparingTo("125.00");
    }

    @Test
    @DisplayName("no allowance bound: zero to start from")
    void noAllowance() {
        assertThat(SimulatedBalance.of(null, List.of(), List.of())).isEqualByComparingTo("0");
    }
}
