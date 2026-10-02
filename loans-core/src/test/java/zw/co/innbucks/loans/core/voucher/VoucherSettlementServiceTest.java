package zw.co.innbucks.loans.core.voucher;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.CurrencyTotals;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.Event;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.Line;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.MerchantTotals;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport.OutletTotals;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The daily settlement report (FR-SGL-038): one market day, totals per currency and per merchant, by outlet, and every
 * event; for every merchant or for one.
 */
class VoucherSettlementServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);
    /** 4 October 2026, 06:30 in Harare. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 4, 30);
    /** Market day 3 October in Harare, as stored UTC bounds. */
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 2, 22, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 3, 21, 59, 59, 999_999_999);

    private final VoucherCodeVault vault = VoucherCodeVaultTest.vault();
    private MerchantRepository merchants;
    private VoucherSettlementService service;

    @BeforeEach
    void setUp() {
        Voucher seven = TestVouchers.issued(vault).status(VoucherStatus.PARTIALLY_REDEEMED)
                .redeemedAmount(new BigDecimal("180.00")).build();
        Voucher eight = TestVouchers.issued(vault).id(8L).loanAccount("SGL-2026-000151").customerReference("E1001")
                .codeLast4("2151").faceValue(new BigDecimal("250.00")).issuedAt(LocalDateTime.of(2026, 10, 3, 6, 5, 41))
                .build();
        Voucher lapsed = TestVouchers.issued(vault).id(5L).loanAccount("SGL-2026-000120").customerReference("E1043")
                .codeLast4("7782").status(VoucherStatus.PARTIALLY_REDEEMED)
                .redeemedAmount(new BigDecimal("60.00")).faceValue(new BigDecimal("200.00"))
                .expiresAt(LocalDateTime.of(2026, 10, 3, 21, 59, 59, 999_999_000))
                .merchant(TestVouchers.merchant(9L, "pick-n-pay", "Pick n Pay")).build();
        VoucherRepository vouchers = mock(VoucherRepository.class);
        when(vouchers.findByIssuedAtBetweenOrderByIdAsc(FROM, TO)).thenReturn(List.of(eight));
        when(vouchers.findByCancelledAtBetweenOrderByIdAsc(FROM, TO)).thenReturn(List.of());
        when(vouchers.findByExpiresAtBetweenAndStatusInOrderByIdAsc(eq(FROM), eq(TO), anyCollection()))
                .thenReturn(List.of(lapsed));
        when(vouchers.findAllById(any())).thenReturn(List.of(seven));
        VoucherRedemptionRepository redemptions = mock(VoucherRedemptionRepository.class);
        when(redemptions.findByRedeemedAtBetweenOrderByIdAsc(FROM, TO)).thenReturn(List.of(VoucherRedemption.builder()
                .id(31L).voucherId(7L).merchantId(3L).merchantReference("GM-POS-88412").amount(new BigDecimal("180.00"))
                .balanceAfter(new BigDecimal("120.00")).outletId("GM-AVD-01").outletName("GetMore Avondale")
                .redeemedBy("getmore-pos").redeemedAt(LocalDateTime.of(2026, 10, 3, 15, 42, 10)).build()));
        merchants = mock(MerchantRepository.class);
        when(merchants.existsByMerchantCode("getmore-groceries")).thenReturn(true);
        when(merchants.existsByMerchantCode("pick-n-pay")).thenReturn(true);
        service = new VoucherSettlementService(vouchers, merchants, redemptions,
                new MarketTimeZone("ZW", Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("issued, redeemed by outlet and lapsed-with-balance, totalled per currency and per merchant, in time"
            + " order")
    void report() {
        VoucherSettlementReport report = service.report(DAY, null);

        assertThat(report.merchantCode()).isNull();
        assertThat(report.totals()).containsExactly(new CurrencyTotals("USD", 1, new BigDecimal("250.00"), 1,
                new BigDecimal("180.00"), 0, new BigDecimal("0.00"), 1, new BigDecimal("140.00")));
        assertThat(report.byMerchant()).containsExactly(
                new MerchantTotals("getmore-groceries", "GetMore Groceries", "USD", 1, new BigDecimal("250.00"), 1,
                        new BigDecimal("180.00"), 0, new BigDecimal("0.00"), 0, new BigDecimal("0.00")),
                new MerchantTotals("pick-n-pay", "Pick n Pay", "USD", 0, new BigDecimal("0.00"), 0,
                        new BigDecimal("0.00"), 0, new BigDecimal("0.00"), 1, new BigDecimal("140.00")));
        assertThat(report.redemptionsByOutlet()).containsExactly(new OutletTotals("getmore-groceries", "GM-AVD-01",
                "GetMore Avondale", "USD", 1, new BigDecimal("180.00")));
        assertThat(report.lines()).extracting(Line::event, Line::voucherId, Line::merchantCode, Line::maskedCode)
                .containsExactly(tuple(Event.ISSUED, 8L, "getmore-groceries", "**** **** **** 2151"),
                        tuple(Event.REDEEMED, 7L, "getmore-groceries", "**** **** **** 8406"),
                        tuple(Event.EXPIRED, 5L, "pick-n-pay", "**** **** **** 7782"));
    }

    @Test
    @DisplayName("for one merchant: only its vouchers' events, and totals of them alone")
    void oneMerchant() {
        VoucherSettlementReport report = service.report(DAY, " pick-n-pay ");

        assertThat(report.merchantCode()).isEqualTo("pick-n-pay");
        assertThat(report.lines()).extracting(Line::event, Line::voucherId).containsExactly(tuple(Event.EXPIRED, 5L));
        assertThat(report.totals()).containsExactly(new CurrencyTotals("USD", 0, new BigDecimal("0.00"), 0,
                new BigDecimal("0.00"), 0, new BigDecimal("0.00"), 1, new BigDecimal("140.00")));
        assertThat(report.byMerchant()).extracting(MerchantTotals::merchantCode).containsExactly("pick-n-pay");
        assertThat(report.redemptionsByOutlet()).isEmpty();
    }

    @Test
    @DisplayName("a merchant code no merchant has is a 404, not an empty report")
    void unknownMerchant() {
        assertThatThrownBy(() -> service.report(DAY, "nobody")).isInstanceOf(NotFoundException.class)
                .hasMessage("Merchant nobody not found");
    }

    @Test
    @DisplayName("the CSV is one row per event, times at the market offset, codes masked")
    void csv() {
        assertThat(service.csv(DAY, null)).isEqualTo("""
                event,at,voucherId,maskedCode,loanAccount,customerReference,currency,amount,outletId,outletName,\
                merchantReference,merchantCode\r
                ISSUED,2026-10-03T08:05:41+02:00,8,**** **** **** 2151,SGL-2026-000151,E1001,USD,250.00,,,,\
                getmore-groceries\r
                REDEEMED,2026-10-03T17:42:10+02:00,7,**** **** **** 8406,SGL-2026-000143,E1012,USD,180.00,GM-AVD-01,\
                GetMore Avondale,GM-POS-88412,getmore-groceries\r
                EXPIRED,2026-10-03T23:59:59+02:00,5,**** **** **** 7782,SGL-2026-000120,E1043,USD,140.00,,,,\
                pick-n-pay\r
                """);
        assertThat(service.csv(DAY, "pick-n-pay")).endsWith(",,,,pick-n-pay\r\n").doesNotContain("getmore-groceries");
    }

    @Test
    @DisplayName("a value that could run as a formula in a spreadsheet is neutralised, and commas are quoted")
    void cellsAreSafe() {
        assertThat(VoucherSettlementService.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(VoucherSettlementService.cell("+263")).isEqualTo("'+263");
        assertThat(VoucherSettlementService.cell("GetMore, Avondale")).isEqualTo("\"GetMore, Avondale\"");
        assertThat(VoucherSettlementService.cell(null)).isEmpty();
    }

    @Test
    @DisplayName("a day still to come is refused")
    void futureDay() {
        assertThatThrownBy(() -> service.report(LocalDate.of(2026, 10, 5), null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("date (2026-10-05) is in the future");
    }
}
