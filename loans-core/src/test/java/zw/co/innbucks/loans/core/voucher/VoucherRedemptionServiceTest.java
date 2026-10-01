package zw.co.innbucks.loans.core.voucher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GetMore's till (FR-SGL-036): checking a voucher, spending it in part or in full, every refusal by its code, and a
 * retried redemption answered once rather than spent twice.
 */
class VoucherRedemptionServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 15, 42, 10);

    private final VoucherCodeVault vault = VoucherCodeVaultTest.vault();
    private final VoucherProperties properties = new VoucherProperties();
    private VoucherRepository vouchers;
    private VoucherRedemptionRepository redemptions;
    private Voucher voucher;
    private VoucherRedemptionService service;

    @BeforeEach
    void setUp() {
        voucher = TestVouchers.issued(vault).build();
        vouchers = mock(VoucherRepository.class);
        when(vouchers.lockByCodeHmac(vault.fingerprint(TestVouchers.CODE))).thenAnswer(i -> Optional.of(voucher));
        when(vouchers.findByCodeHmac(vault.fingerprint(TestVouchers.CODE))).thenAnswer(i -> Optional.of(voucher));
        when(vouchers.save(any())).thenAnswer(i -> i.getArgument(0));
        redemptions = mock(VoucherRedemptionRepository.class);
        when(redemptions.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        AuthService auth = mock(AuthService.class);
        when(auth.getLoggedInUsername()).thenReturn("getmore-pos");
        service = new VoucherRedemptionService(vouchers, redemptions, vault, properties, auth, mock(AuditService.class),
                new MarketTimeZone("ZW", Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)));
    }

    private static VoucherRedemptionRequest redeem(String code, String amount, String reference) {
        return new VoucherRedemptionRequest(code, new BigDecimal(amount), "USD", "GM-AVD-01", "GetMore Avondale",
                reference);
    }

    private static void refused(Runnable call, VoucherRefusal refusal) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(VoucherRefusedException.class,
                ex -> assertThat(ex.getRefusal()).isEqualTo(refusal));
    }

    @Test
    @DisplayName("checking: status, balance, expiry and the holder as far as a receipt needs; nothing spent")
    void validate() {
        VoucherValidationResponse answer = service.validate(new VoucherValidationRequest("4829 1506 7331 8406",
                "GM-AVD-01"));

        assertThat(answer.status()).isEqualTo(VoucherStatus.ISSUED);
        assertThat(answer.redeemable()).isTrue();
        assertThat(answer.balance()).isEqualByComparingTo("300.00");
        assertThat(answer.holderName()).isEqualTo("Chipo B.");
        assertThat(answer.maskedCode()).isEqualTo("**** **** **** 8406");
        verify(vouchers, never()).save(any());
    }

    @Test
    @DisplayName("a malformed code is refused before any lookup; a well-formed one no voucher has is not found")
    void unknownCodes() {
        refused(() -> service.validate(new VoucherValidationRequest("4829 1506 7331 8407", "GM-AVD-01")),
                VoucherRefusal.INVALID_VOUCHER_CODE);
        verify(vouchers, never()).findByCodeHmac(anyString());
        refused(() -> service.redeem(redeem("7391-0452-8867-2151", "10.00", "R1")), VoucherRefusal.VOUCHER_NOT_FOUND);
    }

    @Test
    @DisplayName("spent in part, then the rest: PARTIALLY_REDEEMED, then REDEEMED, each purchase on record")
    void partThenAll() {
        VoucherRedemptionResult first = service.redeem(redeem("4829-1506-7331-8406", "180.00", "GM-POS-88412"));

        assertThat(first.status()).isEqualTo(VoucherStatus.PARTIALLY_REDEEMED);
        assertThat(first.balanceAfter()).isEqualByComparingTo("120.00");
        assertThat(first.replayed()).isFalse();
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.PARTIALLY_REDEEMED);
        assertThat(voucher.getLastRedeemedOutlet()).isEqualTo("GetMore Avondale");

        VoucherRedemptionResult rest = service.redeem(redeem("4829150673318406", "120.00", "GM-POS-88501"));

        assertThat(rest.status()).isEqualTo(VoucherStatus.REDEEMED);
        assertThat(rest.balanceAfter()).isEqualByComparingTo("0.00");
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.REDEEMED);
        refused(() -> service.redeem(redeem(TestVouchers.CODE, "1.00", "GM-POS-88502")),
                VoucherRefusal.VOUCHER_REDEEMED);
    }

    @Test
    @DisplayName("more than is left is refused, naming the balance; nothing is spent")
    void insufficient() {
        assertThatThrownBy(() -> service.redeem(redeem(TestVouchers.CODE, "300.01", "R1")))
                .isInstanceOfSatisfying(VoucherRefusedException.class, ex -> {
                    assertThat(ex.getRefusal()).isEqualTo(VoucherRefusal.INSUFFICIENT_BALANCE);
                    assertThat(ex.getMessage()).isEqualTo("The amount is more than the USD 300.00 left on this"
                            + " voucher");
                });
        verify(redemptions, never()).saveAndFlush(any());
        assertThat(voucher.getRedeemedAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("with partial redemption off, only the whole balance is accepted")
    void partialOff() {
        properties.setPartialRedemptionAllowed(false);

        refused(() -> service.redeem(redeem(TestVouchers.CODE, "100.00", "R1")),
                VoucherRefusal.PARTIAL_REDEMPTION_NOT_ALLOWED);
        assertThat(service.redeem(redeem(TestVouchers.CODE, "300.00", "R2")).status())
                .isEqualTo(VoucherStatus.REDEEMED);
    }

    @Test
    @DisplayName("an expired, cancelled or foreign-currency voucher is refused by its own code")
    void closedVouchers() {
        voucher = TestVouchers.issued(vault).expiresAt(NOW).build();
        refused(() -> service.redeem(redeem(TestVouchers.CODE, "1.00", "R1")), VoucherRefusal.VOUCHER_EXPIRED);
        assertThat(service.validate(new VoucherValidationRequest(TestVouchers.CODE, "GM-AVD-01")).redeemable())
                .isFalse();

        voucher = TestVouchers.issued(vault).status(VoucherStatus.CANCELLED).build();
        refused(() -> service.redeem(redeem(TestVouchers.CODE, "1.00", "R1")), VoucherRefusal.VOUCHER_CANCELLED);

        voucher = TestVouchers.issued(vault).currency("ZWG").build();
        refused(() -> service.redeem(redeem(TestVouchers.CODE, "1.00", "R1")), VoucherRefusal.CURRENCY_MISMATCH);
        verify(redemptions, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("the same reference again for the same voucher and amount gets the first answer; nothing more spent")
    void retrySameReference() {
        VoucherRedemption earlier = VoucherRedemption.builder().id(31L).voucherId(7L).merchantReference("GM-POS-88412")
                .amount(new BigDecimal("180.00")).balanceAfter(new BigDecimal("120.00")).outletId("GM-AVD-01")
                .redeemedBy("getmore-pos").redeemedAt(NOW.minusSeconds(5)).build();
        when(redemptions.findByMerchantReference("GM-POS-88412")).thenReturn(Optional.of(earlier));

        VoucherRedemptionResult again = service.redeem(redeem(TestVouchers.CODE, "180.00", "GM-POS-88412"));

        assertThat(again.replayed()).isTrue();
        assertThat(again.balanceAfter()).isEqualByComparingTo("120.00");
        verify(redemptions, never()).saveAndFlush(any());
        verify(vouchers, never()).save(any());

        refused(() -> service.redeem(redeem(TestVouchers.CODE, "50.00", "GM-POS-88412")),
                VoucherRefusal.REFERENCE_REUSED);
    }

    @Test
    @DisplayName("a reference another voucher took at the same moment is refused, and this redemption rolls back")
    void referenceRace() {
        when(redemptions.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_voucher_redemptions"));

        refused(() -> service.redeem(redeem(TestVouchers.CODE, "10.00", "GM-POS-1")), VoucherRefusal.REFERENCE_REUSED);
    }
}
