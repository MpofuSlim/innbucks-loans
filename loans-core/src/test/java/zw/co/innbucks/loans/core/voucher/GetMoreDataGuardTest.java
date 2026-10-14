package zw.co.innbucks.loans.core.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.simulator.SimCouponRepository;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.merchant.VoucherStore;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GetMore's boot checks against the data: a mode that needs the voucher code keys refuses to start without them,
 * SIMULATED refuses a database holding real (LIVE) GetMore vouchers, and the rest only warn. The merchant warning is
 * the one {@code GET /lending/v1/getmore} shows too.
 */
class GetMoreDataGuardTest {

    private final VoucherCodeVault vault = mock(VoucherCodeVault.class);
    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final SimCouponRepository simCoupons = mock(SimCouponRepository.class);
    private final MerchantRepository merchants = mock(MerchantRepository.class);

    private GetMoreDataGuard guard(GetMoreMode mode) {
        GetMoreProperties properties = new GetMoreProperties();
        properties.setMode(mode);
        return new GetMoreDataGuard(properties, vault, vouchers, simCoupons, merchants);
    }

    private static Merchant merchant(VoucherStore store) {
        return Merchant.builder().merchantCode("getmore-groceries").companyName("GetMore Groceries")
                .voucherStore(store).build();
    }

    @Test
    @DisplayName("LIVE or SIMULATED without the voucher code keys: refused, naming the variables and never a value")
    void codeKeysRequired() {
        when(vault.isConfigured()).thenReturn(false);
        for (GetMoreMode mode : new GetMoreMode[]{GetMoreMode.LIVE, GetMoreMode.SIMULATED}) {
            assertThatThrownBy(() -> guard(mode))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("GETMORE_MODE is " + mode + " but VOUCHER_CODE_HMAC_KEY and VOUCHER_CODE_ENCRYPTION_KEY"
                            + " are required to store GetMore codes: set both, or set GETMORE_MODE=OFF");
        }
        assertThat(guard(GetMoreMode.OFF).mode()).as("OFF stores no GetMore code").isEqualTo(GetMoreMode.OFF);
    }

    @Test
    @DisplayName("SIMULATED on a database that holds LIVE GetMore vouchers: refused, it is a production database")
    void simulatedNeverOnRealCoupons() {
        when(vault.isConfigured()).thenReturn(true);
        when(vouchers.existsByStoreMode(GetMoreMode.LIVE)).thenReturn(true);

        assertThatThrownBy(() -> guard(GetMoreMode.SIMULATED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("this database holds real GetMore vouchers (store mode LIVE)")
                .hasMessageContaining("Set GETMORE_MODE=LIVE or OFF");
        assertThatCode(() -> guard(GetMoreMode.LIVE)).as("LIVE on its own data").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("simulated rows on a LIVE or OFF host, and simulated vouchers on LIVE: warned about, never refused")
    void leftoversOnlyWarn() {
        when(vault.isConfigured()).thenReturn(true);
        when(simCoupons.count()).thenReturn(3L);
        when(vouchers.countByStoreMode(GetMoreMode.SIMULATED)).thenReturn(2L);

        assertThat(guard(GetMoreMode.LIVE).mode()).isEqualTo(GetMoreMode.LIVE);
        assertThat(guard(GetMoreMode.OFF).mode()).isEqualTo(GetMoreMode.OFF);
        verify(vouchers).countByStoreMode(GetMoreMode.SIMULATED);
    }

    @Test
    @DisplayName("SIMULATED on staging's own data starts, and does not count its own simulated rows")
    void simulatedOnItsOwnData() {
        when(vault.isConfigured()).thenReturn(true);
        when(merchants.findByStaffLoanMerchantTrue()).thenReturn(Optional.of(merchant(VoucherStore.GETMORE)));

        assertThat(guard(GetMoreMode.SIMULATED).mode()).isEqualTo(GetMoreMode.SIMULATED);
        verify(simCoupons, never()).count();
    }

    @Test
    @DisplayName("the merchant warning: GetMore on and the Staff Grocery Loan merchant not a GetMore store")
    void merchantWarning() {
        String warning = "Accepted loans will wait AWAITING_DISBURSEMENT with no voucher: the Staff Grocery Loan"
                + " merchant getmore-groceries is not a GetMore store";
        assertThat(GetMoreDataGuard.merchantWarning(GetMoreMode.SIMULATED, merchant(VoucherStore.LOANS)))
                .contains(warning);
        assertThat(GetMoreDataGuard.merchantWarning(GetMoreMode.LIVE, merchant(VoucherStore.LOANS)))
                .contains(warning);
        assertThat(GetMoreDataGuard.merchantWarning(GetMoreMode.OFF, merchant(VoucherStore.LOANS)))
                .as("OFF issues no GetMore voucher anyway").isEmpty();
        assertThat(GetMoreDataGuard.merchantWarning(GetMoreMode.LIVE, merchant(VoucherStore.GETMORE))).isEmpty();
        assertThat(GetMoreDataGuard.merchantWarning(GetMoreMode.LIVE, null)).as("no merchant set").isEmpty();
    }
}
