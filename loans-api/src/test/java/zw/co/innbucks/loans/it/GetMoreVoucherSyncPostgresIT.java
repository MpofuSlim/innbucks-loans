package zw.co.innbucks.loans.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.CouponRequest;
import zw.co.innbucks.loans.core.getmore.Deadline;
import zw.co.innbucks.loans.core.getmore.FindOrCreate;
import zw.co.innbucks.loans.core.getmore.GenerateGuard;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupons;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.simulator.SimSale;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatedTill;
import zw.co.innbucks.loans.core.voucher.BorrowerVoucherSpendsResponse;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult.Outcome;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncTrigger;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherCommand;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherSync;
import zw.co.innbucks.loans.core.voucher.VoucherResponse;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementReport;
import zw.co.innbucks.loans.core.voucher.VoucherSettlementService;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.core.voucher.VoucherStopReason;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A GetMore voucher's spends read back into loans against real Postgres and the simulated store: every row the sync
 * writes must pass V37's constraints, a voucher spent in parts, refunded, held at the till and expired must stand
 * where the constraints say it can, and two syncs racing on one voucher must record each sale once. The loan is
 * written by SQL; the coupon, the voucher and everything after go through the application's own code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "it"})
@EnabledIf("zw.co.innbucks.loans.it.ItPostgres#available")
@TestPropertySource(properties = {
        "loans.getmore.mode=SIMULATED",
        "loans.getmore.simulator.api-key=it-sim-3f9a1c7e5b2d4f6a8c0e1b3d5f7a9c2e4b6d8f0a1c3e",
        "loans.vouchers.code-hmac-key=it-voucher-hmac-key-0123456789abcdef0123456789",
        "loans.vouchers.code-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
class GetMoreVoucherSyncPostgresIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = ItPostgres.createDatabase("loans_getmore_sync_it");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", ItPostgres::user);
        registry.add("spring.datasource.password", ItPostgres::password);
    }

    @Autowired private GetMoreCoupons coupons;
    @Autowired private SimulatedTill till;
    @Autowired private VoucherService vouchers;
    @Autowired private GetMoreVoucherSync sync;
    @Autowired private VoucherSettlementService settlement;
    @Autowired private MarketTimeZone marketTimeZone;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    /** A voucher issued as acceptance will issue it: a coupon on a fresh prefix, kept for a paid-out loan. */
    private record Issued(long voucherId, long staffMemberId, String reference, String code) {
    }

    private Issued issue(String employee, String msisdn, String reference) {
        String prefix = GetMoreCodes.prefixFor("SIM-", GetMoreCodes.newNonce());
        GenerateGuard guard = new GenerateGuard() {
            @Override
            public void beforeGenerate() {
            }

            @Override
            public void notProcessed() {
            }
        };
        LocalDateTime now = marketTimeZone.nowUtc();
        FindOrCreate found = coupons.findOrCreate(new CouponRequest(prefix, 43, new BigDecimal("150.00"), "USD", 16,
                marketTimeZone.today().plusDays(30), now.minusMinutes(4), guard), Deadline.none());
        GetMoreCoupon coupon = ((FindOrCreate.Bound) found).coupon();
        long member = StaffLoanRows.member(jdbc, employee, msisdn);
        long loan = StaffLoanRows.loan(jdbc, member, reference, "DISBURSED", now,
                VoucherService.getMoreDisbursementReference(GetMoreMode.SIMULATED, coupon.couponId()));
        VoucherResponse voucher = new TransactionTemplate(transactionManager).execute(status ->
                vouchers.issueFromGetMore(new GetMoreVoucherCommand(loan, reference, merchant(), member, employee,
                        "Rutendo Chari", "0" + msisdn.substring(3), new BigDecimal("150"), "USD",
                        GetMoreMode.SIMULATED, coupon.couponId(), 43L, coupon.code(), prefix)));
        return new Issued(voucher.id(), member, reference, coupon.code());
    }

    private long merchant() {
        return jdbc.queryForObject("SELECT id FROM merchants WHERE merchant_code = 'getmore-groceries'", Long.class);
    }

    private SimSale sell(Issued voucher, String amount, boolean invoiced) {
        return till.sell(voucher.code(), new BigDecimal(amount), null, null, "GM-AVD", "GetMore Avondale", null,
                invoiced);
    }

    private GetMoreSyncResult sync(Issued voucher) {
        return sync.sync(voucher.voucherId(), GetMoreSyncTrigger.SIMULATOR);
    }

    private Map<String, Object> row(Issued voucher) {
        return jdbc.queryForMap("SELECT status, redeemed_amount, refunded_amount, held_amount, stopped_reason,"
                + " coupon_withdrawn_at, synced_at, sync_blocked_by, last_redeemed_at FROM vouchers WHERE id = ?",
                voucher.voucherId());
    }

    @Test
    @DisplayName("spent in parts, refunded, held, read in the borrower's view and the day's report, then expired")
    void aVoucherThroughItsLife() {
        Issued voucher = issue("E1064", "263771640064", "SGL-2026-000164");
        assertThat(row(voucher)).containsEntry("status", "ISSUED");

        SimSale first = sell(voucher, "45.50", true);
        GetMoreSyncResult afterFirst = sync(voucher);
        assertThat(afterFirst.outcome()).isEqualTo(Outcome.SYNCED);
        assertThat(afterFirst.spendsRecorded()).isEqualTo(1);
        assertThat(row(voucher)).containsEntry("status", "PARTIALLY_REDEEMED")
                .containsEntry("redeemed_amount", new BigDecimal("45.50"));

        sell(voucher, "54.50", true);
        till.refund(first.orderNumber(), new BigDecimal("10.00"), null);
        GetMoreSyncResult afterRefund = sync(voucher);
        assertThat(afterRefund.spendsRecorded()).isEqualTo(1);
        assertThat(afterRefund.refundsRecorded()).isEqualTo(1);
        assertThat(row(voucher)).containsEntry("redeemed_amount", new BigDecimal("90.00"))
                .containsEntry("refunded_amount", new BigDecimal("10.00"));
        assertThat(jdbc.queryForList("SELECT merchant_reference FROM voucher_redemptions WHERE voucher_id = ?"
                + " ORDER BY id", String.class, voucher.voucherId()))
                .containsExactly("GMS:O:" + first.orderId() + ":4550", "GMS:O:" + (first.orderId() + 1) + ":5450");

        SimSale held = sell(voucher, "20.00", false);
        GetMoreSyncResult afterHeld = sync(voucher);
        assertThat(afterHeld.heldAmount()).isEqualByComparingTo("20.00");
        assertThat(afterHeld.liveOrdersOnCoupon()).isEqualTo(3);
        BorrowerVoucherSpendsResponse spends = vouchers.spendsFor(voucher.staffMemberId(), voucher.reference());
        assertThat(spends.balance()).as("less what is held at the till").isEqualByComparingTo("40.00");
        assertThat(spends.movements()).extracting(BorrowerVoucherSpendsResponse.Movement::kind,
                        BorrowerVoucherSpendsResponse.Movement::balanceAfter)
                .containsExactly(tuple(BorrowerVoucherSpendsResponse.Kind.SPEND, new BigDecimal("104.50")),
                        tuple(BorrowerVoucherSpendsResponse.Kind.SPEND, new BigDecimal("50.00")),
                        tuple(BorrowerVoucherSpendsResponse.Kind.REFUND, new BigDecimal("60.00")));

        till.invoice(held.orderNumber());
        assertThat(sync(voucher).spendsRecorded()).isEqualTo(1);
        assertThat(row(voucher)).containsEntry("redeemed_amount", new BigDecimal("110.00"))
                .containsEntry("held_amount", new BigDecimal("0.00"));

        VoucherSettlementReport report = settlement.report(marketTimeZone.today(), "getmore-groceries");
        assertThat(report.simulated()).isTrue();
        assertThat(report.lines()).filteredOn(line -> line.voucherId() == voucher.voucherId())
                .extracting(VoucherSettlementReport.Line::event).containsExactlyInAnyOrder(
                        VoucherSettlementReport.Event.ISSUED, VoucherSettlementReport.Event.REDEEMED,
                        VoucherSettlementReport.Event.REDEEMED, VoucherSettlementReport.Event.REDEEMED,
                        VoucherSettlementReport.Event.REFUNDED);

        till.expire(voucher.code(), marketTimeZone.nowUtc());
        assertThat(vouchers.expireNowForSimulation(voucher.voucherId()).status()).isEqualTo(VoucherStatus.EXPIRED);
        GetMoreSyncResult afterExpiry = sync(voucher);
        assertThat(afterExpiry.couponWithdrawn()).isTrue();
        assertThat(row(voucher)).containsEntry("status", "EXPIRED")
                .containsEntry("stopped_reason", VoucherStopReason.SIMULATOR_EXPIRED.name());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM getmore_sim_coupons WHERE upper(code) ="
                + " upper(?)", Boolean.class, voucher.code())).as("withdrawn at the store").isTrue();
    }

    @Test
    @DisplayName("refunded back to nothing: ISSUED again, with the last spend still on record")
    void refundedBackToNothing() {
        Issued voucher = issue("E1065", "263771640065", "SGL-2026-000165");
        SimSale sale = sell(voucher, "45.50", true);
        sync(voucher);
        till.refund(sale.orderNumber(), null, null);

        assertThat(sync(voucher).refundsRecorded()).isEqualTo(1);
        Map<String, Object> row = row(voucher);
        assertThat(row).containsEntry("status", "ISSUED").containsEntry("redeemed_amount", new BigDecimal("0.00"))
                .containsEntry("refunded_amount", new BigDecimal("45.50"));
        assertThat(row.get("last_redeemed_at")).isNotNull();
    }

    @Test
    @DisplayName("syncs racing on one voucher record each sale and refund exactly once")
    void racingSyncsRecordOnce() throws Exception {
        Issued voucher = issue("E1066", "263771640066", "SGL-2026-000166");
        SimSale sale = sell(voucher, "30.00", true);
        sell(voucher, "25.00", true);
        till.refund(sale.orderNumber(), new BigDecimal("5.00"), null);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<GetMoreSyncResult>> syncs = java.util.Collections.nCopies(4, () -> {
                start.await(10, TimeUnit.SECONDS);
                return sync(voucher);
            });
            List<Future<GetMoreSyncResult>> futures = syncs.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<GetMoreSyncResult> future : futures) {
                assertThat(future.get(60, TimeUnit.SECONDS).outcome()).isIn(Outcome.SYNCED, Outcome.UNCHANGED,
                        Outcome.STALE_SNAPSHOT);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_redemptions WHERE voucher_id = ?", Integer.class,
                voucher.voucherId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_refunds WHERE voucher_id = ?", Integer.class,
                voucher.voucherId())).isEqualTo(1);
        assertThat(row(voucher)).containsEntry("redeemed_amount", new BigDecimal("50.00"));
    }
}
