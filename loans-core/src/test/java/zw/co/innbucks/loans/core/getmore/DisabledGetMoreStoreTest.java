package zw.co.innbucks.loans.core.getmore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OFF store refuses every call as OFF, and, like the LIVE and SIMULATED stores, refuses a call made with a
 * transaction open first: the default mode is where most tests above the port run, so it must not hide a caller that
 * holds a pooled connection across a GetMore call.
 */
class DisabledGetMoreStoreTest {

    private final DisabledGetMoreStore store = new DisabledGetMoreStore();

    @Test
    @DisplayName("every call is refused as OFF")
    void everyCallRefusedAsOff() {
        assertOff(() -> store.salesRule(43));
        assertOff(() -> store.generateCoupons(GetMoreCouponSpec.single(43, "INN-K7Q2M3XW-", 16)));
        assertOff(() -> store.searchCoupons(GetMoreCouponQuery.byPrefix("INN-K7Q2M3XW-", 1, 50)));
        assertOff(() -> store.coupon(1207));
        assertOff(() -> store.updateCoupon(1207, new GetMoreCouponUpdate(43, "INN-K7Q2M3XW-AAAABBBBCCCCDDDD", 1,
                null)));
        assertOff(() -> store.deleteCoupons(List.of("INN-K7Q2M3XW-AAAABBBBCCCCDDDD"), true));
        assertOff(() -> store.orders(GetMoreOrderQuery.byPrefix("INN-K7Q2M3XW-", 1, 50)));
        assertOff(() -> store.creditMemos(new GetMoreCreditMemoQuery(List.of(5012L), 1, 50)));
    }

    @Test
    @DisplayName("a call with a transaction open: IllegalStateException before the OFF refusal, as in every mode")
    void transactionOpenIllegalState() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> store.salesRule(43)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("GetMore is never called with a transaction open");
            assertThatThrownBy(() -> store.generateCoupons(GetMoreCouponSpec.single(43, "INN-K7Q2M3XW-", 16)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> store.deleteCoupons(List.of("INN-K7Q2M3XW-AAAABBBBCCCCDDDD"), true))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private static void assertOff(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(GetMoreUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(GetMoreUnavailableException.Reason.OFF));
    }
}
