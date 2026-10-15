package zw.co.innbucks.loans.core.voucher;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.getmore.GetMoreAmountBinding;
import zw.co.innbucks.loans.core.getmore.GetMoreCalls;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupon;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponSpec;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponUpdate;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupons;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemo;
import zw.co.innbucks.loans.core.getmore.GetMoreCreditMemoQuery;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreNotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreOrder;
import zw.co.innbucks.loans.core.getmore.GetMoreOrderQuery;
import zw.co.innbucks.loans.core.getmore.GetMorePage;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreSalesRule;
import zw.co.innbucks.loans.core.getmore.GetMoreStore;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult.Outcome;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A GetMore sync end to end, over an in-memory store (refusing a call with a transaction open, as every real one does)
 * and in-memory rows: what it skips, what it never writes, the per-voucher lock, the withdrawals it makes at GetMore
 * (a lapsed code, extra coupons on the prefix), a coupon GetMore deleted, a single-use coupon used up, and the issues
 * it records, clears and reopens.
 */
class GetMoreVoucherSyncTest {

    private static final String CODE = TestVouchers.GETMORE_CODE;
    private static final String PREFIX = TestVouchers.GETMORE_PREFIX;
    /** 10:40 in Harare on 14 October 2026. */
    private static final Instant START = Instant.parse("2026-10-14T08:40:00Z");
    private static final LocalDateTime SOLD = LocalDateTime.of(2026, 10, 14, 8, 31, 2);

    private final VoucherCodeVault vault = VoucherCodeVaultTest.vault();
    private MutableClock clock;
    private FakeStore store;
    private Voucher voucher;
    private List<VoucherRedemption> spends;
    private List<VoucherRefund> refunds;
    private List<VoucherSyncIssue> issues;
    private List<String> events;
    private VoucherRepository vouchers;
    private VoucherRedemptionRepository redemptionRepository;
    private AuditService audit;
    private GetMoreAmountBinding binding;
    private GetMoreProperties properties;
    private SimpleMeterRegistry registry;
    private GetMoreVoucherSync sync;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        events = new CopyOnWriteArrayList<>();
        store = new FakeStore(events);
        store.coupons.add(coupon(1207, CODE, 0));
        voucher = TestVouchers.getMoreIssued(vault).build();
        spends = new CopyOnWriteArrayList<>();
        refunds = new CopyOnWriteArrayList<>();
        issues = new CopyOnWriteArrayList<>();

        vouchers = mock(VoucherRepository.class);
        when(vouchers.findById(9L)).thenAnswer(i -> Optional.ofNullable(voucher));
        when(vouchers.lockById(9L)).thenAnswer(i -> {
            events.add("apply");
            return Optional.ofNullable(voucher);
        });
        when(vouchers.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        redemptionRepository = mock(VoucherRedemptionRepository.class);
        when(redemptionRepository.findByVoucherIdOrderByIdAsc(9L)).thenAnswer(i -> List.copyOf(spends));
        when(redemptionRepository.saveAndFlush(any())).thenAnswer(i -> {
            VoucherRedemption row = i.getArgument(0);
            ReflectionTestUtils.setField(row, "id", (long) spends.size() + 1);
            spends.add(row);
            return row;
        });
        VoucherRefundRepository refundRepository = mock(VoucherRefundRepository.class);
        when(refundRepository.findByVoucherIdOrderByIdAsc(9L)).thenAnswer(i -> List.copyOf(refunds));
        when(refundRepository.saveAndFlush(any())).thenAnswer(i -> {
            refunds.add(i.getArgument(0));
            return i.getArgument(0);
        });
        VoucherSyncIssueRepository issueRepository = mock(VoucherSyncIssueRepository.class);
        when(issueRepository.findByVoucherIdOrderByIdAsc(9L)).thenAnswer(i -> List.copyOf(issues));
        when(issueRepository.findByVoucherIdAndClearedAtIsNullOrderByIdAsc(9L))
                .thenAnswer(i -> issues.stream().filter(VoucherSyncIssue::isOpen).toList());
        when(issueRepository.save(any())).thenAnswer(i -> {
            VoucherSyncIssue row = i.getArgument(0);
            if (row.getId() == null) {
                ReflectionTestUtils.setField(row, "id", (long) issues.size() + 1);
                issues.add(row);
            }
            return row;
        });

        audit = mock(AuditService.class);
        binding = mock(GetMoreAmountBinding.class);
        when(binding.partialSpending()).thenReturn(true);
        properties = new GetMoreProperties();
        properties.setMode(GetMoreMode.SIMULATED);
        MarketTimeZone market = new MarketTimeZone("ZW", clock);
        registry = new SimpleMeterRegistry();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", registry);
        GetMoreVoucherLedger ledger = new GetMoreVoucherLedger(vouchers, redemptionRepository, refundRepository,
                issueRepository, market);
        sync = new GetMoreVoucherSync(vouchers, refundRepository, vault,
                new GetMoreCoupons(store, binding, properties, market), store, binding, properties, ledger, audit,
                market, mock(PlatformTransactionManager.class), beans.getBeanProvider(MeterRegistry.class));
    }

    private GetMoreSyncResult sync() {
        return sync.sync(9L, GetMoreSyncTrigger.OPERATOR);
    }

    private double counted(Outcome outcome) {
        return registry.get(GetMoreVoucherSync.METRIC).tag("outcome", outcome.name()).counter().count();
    }

    // ---- what is never asked, and what is never written --------------------------------------------------------

    @Test
    @DisplayName("loans' own voucher is skipped, and one of another store mode is a mismatch: GetMore is not asked")
    void skippedAndMismatched() {
        voucher = TestVouchers.issued(vault).id(9L).build();
        assertThat(sync().outcome()).isEqualTo(Outcome.SKIPPED);

        voucher = TestVouchers.getMoreIssued(vault).storeMode(GetMoreMode.LIVE).build();
        GetMoreSyncResult mismatched = sync();
        assertThat(mismatched.outcome()).isEqualTo(Outcome.MODE_MISMATCH);
        assertThat(mismatched.voucher().id()).isEqualTo(9L);

        assertThat(events).isEmpty();
        verify(vouchers, never()).lockById(anyLong());
        assertThat(counted(Outcome.SKIPPED)).isEqualTo(1);
        assertThat(counted(Outcome.MODE_MISMATCH)).isEqualTo(1);
    }

    @Test
    @DisplayName("GetMore unreadable: UNAVAILABLE, and nothing at all is written")
    void unavailableWritesNothing() {
        store.orders.add(order(12, "45.50", "45.50", SOLD));
        store.orderFailure = new GetMoreUnavailableException(Operation.ORDERS,
                GetMoreUnavailableException.Reason.READ_FAILED);

        GetMoreSyncResult result = sync();

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        verify(vouchers, never()).lockById(anyLong());
        verify(redemptionRepository, never()).saveAndFlush(any());
        verify(audit, never()).record(any());
        assertThat(voucher.getSyncedAt()).isNull();
    }

    @Test
    @DisplayName("a snapshot no newer than the one applied is discarded: nothing written")
    void staleSnapshotDiscarded() {
        voucher = TestVouchers.getMoreIssued(vault).syncedAt(LocalDateTime.ofInstant(START, ZoneOffset.UTC)
                .plusSeconds(5)).build();
        store.orders.add(order(12, "45.50", "45.50", SOLD));

        GetMoreSyncResult result = sync();

        assertThat(result.outcome()).isEqualTo(Outcome.STALE_SNAPSHOT);
        assertThat(spends).isEmpty();
        assertThat(voucher.getRedeemedAmount()).isEqualByComparingTo("0");
        verify(audit, never()).record(any());
    }

    @Test
    @DisplayName("never called with a transaction open")
    void neverInATransaction() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(this::sync).isInstanceOf(IllegalStateException.class);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(events).isEmpty();
    }

    // ---- recording ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a sale and its refund recorded, the voucher moved, each audited once the apply committed")
    void recordsAndAudits() {
        store.orders.add(order(12, "45.50", "45.50", "10.00", SOLD));
        store.memos.add(new GetMoreCreditMemo(3, "SIMCM00000003", 12, 2, new BigDecimal("-10.0000"),
                new BigDecimal("10"), BigDecimal.ZERO, SOLD.plusMinutes(5)));

        GetMoreSyncResult result = sync();

        assertThat(result.outcome()).isEqualTo(Outcome.SYNCED);
        assertThat(result.spendsRecorded()).isEqualTo(1);
        assertThat(result.refundsRecorded()).isEqualTo(1);
        assertThat(result.voucher().redeemedAmount()).isEqualByComparingTo("35.50");
        assertThat(result.voucher().refundedAmount()).isEqualByComparingTo("10.00");
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.PARTIALLY_REDEEMED);
        assertThat(voucher.getSyncedAt()).isEqualTo(LocalDateTime.ofInstant(START, ZoneOffset.UTC));
        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit, atLeastOnce()).record(audited.capture());
        assertThat(audited.getAllValues()).extracting(builder -> builder.build().getEventType())
                .containsExactly("VOUCHER_REDEEMED", "VOUCHER_REFUNDED");
        assertThat(audited.getAllValues()).allSatisfy(builder -> {
            AuditLog row = builder.build();
            assertThat(row.getChannelUsed()).isEqualTo("getmore-sync");
            assertThat(row.getActorId()).isEqualTo("getmore-simulator");
            assertThat(row.getDetail()).doesNotContainIgnoringCase(CODE);
        });

        clock.advance(Duration.ofMinutes(2));
        GetMoreSyncResult again = sync();
        assertThat(again.outcome()).as("read again, nothing new").isEqualTo(Outcome.UNCHANGED);
        assertThat(spends).hasSize(1);
        assertThat(refunds).hasSize(1);
        assertThat(counted(Outcome.SYNCED)).isEqualTo(1);
        assertThat(counted(Outcome.UNCHANGED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a row a concurrent writer added first: CONFLICT_RETRY, nothing audited")
    void conflictRetries() {
        store.orders.add(order(12, "45.50", "45.50", SOLD));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException(
                "uq_voucher_redemptions")).when(redemptionRepository).saveAndFlush(any());

        assertThat(sync().outcome()).isEqualTo(Outcome.CONFLICT_RETRY);
        verify(audit, never()).record(any());
    }

    // ---- the lock -----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("two syncs of one voucher never overlap: the second waits for the first's apply before it reads")
    void lockSerialisesTwoSyncs() throws Exception {
        store.orders.add(order(12, "45.50", "45.50", SOLD));
        CountDownLatch firstReading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        store.onSearch = () -> {
            if (firstReading.getCount() == 1) {
                firstReading.countDown();
                await(release);
            }
        };

        CompletableFuture<GetMoreSyncResult> first = CompletableFuture.supplyAsync(this::sync);
        assertThat(firstReading.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<GetMoreSyncResult> second = CompletableFuture.supplyAsync(() -> {
            clock.advance(Duration.ofSeconds(1));
            return sync();
        });
        Thread.sleep(200);
        assertThat(events).as("the second has not read while the first holds the lock").containsExactly("search");
        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.SYNCED);
        assertThat(second.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(events.stream().filter(e -> e.equals("search") || e.equals("apply")).toList())
                .containsExactly("search", "apply", "search", "apply");
        assertThat(spends).as("recorded exactly once").hasSize(1);
    }

    @Test
    @DisplayName("a sync that cannot get the lock within the wait is BUSY, and does nothing")
    void lockTimeoutIsBusy() throws Exception {
        properties.getSync().setLockWait(Duration.ofMillis(100));
        CountDownLatch firstReading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        store.onSearch = () -> {
            if (firstReading.getCount() == 1) {
                firstReading.countDown();
                await(release);
            }
        };
        CompletableFuture<GetMoreSyncResult> first = CompletableFuture.supplyAsync(this::sync);
        assertThat(firstReading.await(5, TimeUnit.SECONDS)).isTrue();

        GetMoreSyncResult busy = sync();

        assertThat(busy.outcome()).isEqualTo(Outcome.BUSY);
        assertThat(busy.voucher()).isNull();
        assertThat(counted(Outcome.BUSY)).isEqualTo(1);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    @DisplayName("the lock is reentrant: a cancellation holds it across its own syncs")
    void lockIsReentrant() {
        GetMoreSyncResult inside = sync.withVoucherLock(9L, () -> {
            sync();
            clock.advance(Duration.ofSeconds(1));
            return sync();
        });
        assertThat(inside.outcome()).isEqualTo(Outcome.UNCHANGED);
    }

    // ---- withdrawals and stops ----------------------------------------------------------------------------------

    @Test
    @DisplayName("a lapsed voucher's code is withdrawn at GetMore (the only real stop) and marked so")
    void withdrawOnLapse() {
        clock.set(TestVouchers.GETMORE_EXPIRES_AT.plusMinutes(1).toInstant(ZoneOffset.UTC));

        GetMoreSyncResult result = sync();

        assertThat(store.deleted).containsExactly(CODE);
        assertThat(result.couponWithdrawn()).isTrue();
        assertThat(voucher.getCouponWithdrawnAt()).isNotNull();
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.EXPIRED);
        assertThat(voucher.getStoppedReason()).as("lapsed on its own").isNull();

        clock.advance(Duration.ofMinutes(2));
        store.deleted.clear();
        sync();
        assertThat(store.deleted).as("not withdrawn twice").isEmpty();
    }

    @Test
    @DisplayName("a coupon expiry date never confirmed is set by the sync, tried again after a failure, then not again")
    void expiryDateRepeated() {
        store.updateFailure = new GetMoreUnavailableException(Operation.UPDATE_COUPON,
                GetMoreUnavailableException.Reason.NOT_REACHED, "connection refused");

        sync();
        assertThat(voucher.getCouponExpirySetAt()).as("GetMore did not confirm it").isNull();

        store.updateFailure = null;
        clock.advance(Duration.ofMinutes(2));
        sync();
        assertThat(store.expiriesSet).containsExactly(TestVouchers.GETMORE_EXPIRES_AT);
        assertThat(voucher.getCouponExpirySetAt()).isEqualTo(LocalDateTime.ofInstant(clock.instant(),
                ZoneOffset.UTC));

        clock.advance(Duration.ofMinutes(2));
        sync();
        assertThat(store.expiriesSet).as("confirmed once, never sent again").hasSize(1);
    }

    @Test
    @DisplayName("no expiry date is set on a lapsed voucher's coupon (it is withdrawn) nor a closed one's")
    void noExpiryDateWhenClosed() {
        clock.set(TestVouchers.GETMORE_EXPIRES_AT.plusMinutes(1).toInstant(ZoneOffset.UTC));
        sync();
        assertThat(store.deleted).containsExactly(CODE);

        voucher = TestVouchers.getMoreIssued(vault).status(VoucherStatus.CANCELLED).build();
        store.coupons.add(coupon(1207, CODE, 0));
        clock.set(START.plusSeconds(60));
        sync();

        assertThat(store.expiriesSet).isEmpty();
        assertThat(voucher.getCouponExpirySetAt()).isNull();
    }

    @Test
    @DisplayName("other unused coupons on the prefix are withdrawn; a used one stays, as a warning")
    void extrasWithdrawn() {
        store.coupons.add(coupon(1300, PREFIX + "AAAAAAAAAAAA1300", 0));
        store.coupons.add(coupon(1301, PREFIX + "AAAAAAAAAAAA1301", 1));

        GetMoreSyncResult result = sync();

        assertThat(store.deleted).containsExactly(PREFIX + "AAAAAAAAAAAA1300");
        assertThat(result.issues()).extracting(VoucherSyncIssueResponse::kind, VoucherSyncIssueResponse::externalRef,
                VoucherSyncIssueResponse::blocking).containsExactly(tuple(VoucherSyncIssueKind.EXTRA_COUPON,
                "coupon:1301", false));
        assertThat(result.issues().getFirst().detail()).contains("****1301").doesNotContain("AAAAAAAAAAAA");
        assertThat(voucher.getStatus()).as("a warning stops nothing").isEqualTo(VoucherStatus.ISSUED);
    }

    @Test
    @DisplayName("the voucher's coupon missing and GetMore answering No such entity: stopped STORE_WITHDRAWN")
    void couponGone() {
        store.coupons.clear();

        GetMoreSyncResult result = sync();

        assertThat(store.lookedUp).containsExactly(1207L);
        assertThat(voucher.getStoppedReason()).isEqualTo(VoucherStopReason.STORE_WITHDRAWN);
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.EXPIRED);
        assertThat(voucher.getCouponWithdrawnAt()).isNotNull();
        assertThat(store.deleted).as("nothing left to withdraw").isEmpty();
        assertThat(result.issues()).extracting(VoucherSyncIssueResponse::kind)
                .containsExactly(VoucherSyncIssueKind.COUPON_GONE);
        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit).record(audited.capture());
        assertThat(audited.getValue().build().getDetail()).startsWith("reason:STORE_WITHDRAWN");

        clock.advance(Duration.ofMinutes(2));
        assertThat(sync().issues()).as("kept open while the voucher stays stopped for it")
                .extracting(VoucherSyncIssueResponse::kind).containsExactly(VoucherSyncIssueKind.COUPON_GONE);
    }

    @Test
    @DisplayName("missing from the search but still there by id (the search lags): nothing is stopped")
    void couponFoundById() {
        store.coupons.clear();
        store.byId.add(coupon(1207, CODE, 0));

        sync();

        assertThat(voucher.getStoppedReason()).isNull();
        assertThat(voucher.getCouponWithdrawnAt()).isNull();
    }

    @Test
    @DisplayName("a single-use coupon used once for less than its value: stopped SINGLE_USE_EXHAUSTED")
    void singleUseExhausted() {
        when(binding.partialSpending()).thenReturn(false);
        store.coupons.set(0, coupon(1207, CODE, 1));
        store.orders.add(order(12, "45.50", "45.50", SOLD));

        GetMoreSyncResult result = sync();

        assertThat(spends).hasSize(1);
        assertThat(voucher.getStoppedReason()).isEqualTo(VoucherStopReason.SINGLE_USE_EXHAUSTED);
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.EXPIRED);
        assertThat(voucher.getRedeemedAmount()).isEqualByComparingTo("45.50");
        assertThat(result.issues()).extracting(VoucherSyncIssueResponse::kind)
                .containsExactly(VoucherSyncIssueKind.COUPON_EXHAUSTED);
    }

    @Test
    @DisplayName("spending in parts allowed: a coupon used once is not exhausted")
    void partialSpendingNotExhausted() {
        store.coupons.set(0, coupon(1207, CODE, 1));
        store.orders.add(order(12, "45.50", "45.50", SOLD));

        sync();

        assertThat(voucher.getStoppedReason()).isNull();
        assertThat(voucher.getStatus()).isEqualTo(VoucherStatus.PARTIALLY_REDEEMED);
    }

    // ---- issues -------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("issues: opened once and audited once, seen again, cleared when gone, reopened when back")
    void issuesUpsertClearAndReopen() {
        GetMoreOrder zwg = order(12, "45.50", "45.50", SOLD, "ZWG");
        store.orders.add(zwg);

        GetMoreSyncResult first = sync();
        assertThat(first.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(voucher.getSyncBlockedBy()).isEqualTo(VoucherSyncIssueKind.CURRENCY_MISMATCH);
        assertThat(issues).singleElement().satisfies(issue -> {
            assertThat(issue.isBlocking()).isTrue();
            assertThat(issue.getExternalRef()).isEqualTo("order:12");
        });
        LocalDateTime firstSeen = issues.getFirst().getFirstSeenAt();

        clock.advance(Duration.ofMinutes(2));
        sync();
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst().getLastSeenAt()).isAfter(firstSeen);
        assertThat(issues.getFirst().getFirstSeenAt()).isEqualTo(firstSeen);

        clock.advance(Duration.ofMinutes(2));
        store.orders.set(0, order(12, "45.50", "45.50", SOLD));
        GetMoreSyncResult fixed = sync();
        assertThat(fixed.outcome()).isEqualTo(Outcome.SYNCED);
        assertThat(fixed.issues()).isEmpty();
        assertThat(issues.getFirst().getClearedAt()).isNotNull();
        assertThat(voucher.getSyncBlockedBy()).isNull();
        assertThat(spends).hasSize(1);

        clock.advance(Duration.ofMinutes(2));
        store.orders.set(0, zwg);
        assertThat(sync().outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(issues).as("the same row, reopened").hasSize(1);
        assertThat(issues.getFirst().isOpen()).isTrue();

        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit, atLeastOnce()).record(audited.capture());
        assertThat(audited.getAllValues()).extracting(builder -> builder.build().getEventType())
                .containsExactly("VOUCHER_SYNC_ISSUE", "VOUCHER_REDEEMED", "VOUCHER_SYNC_ISSUE");
        assertThat(registry.get(GetMoreVoucherSync.ISSUES_METRIC).tag("kind", "CURRENCY_MISMATCH").counter().count())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an incomplete read clears nothing it cannot see")
    void incompleteClearsNothing() {
        store.orders.add(order(12, "45.50", "45.50", SOLD, "ZWG"));
        sync();
        assertThat(issues).singleElement().satisfies(issue -> assertThat(issue.isOpen()).isTrue());

        clock.advance(Duration.ofMinutes(2));
        store.orders.clear();
        store.orderTotalOverride = 50 * 21;   // more pages than one sync reads
        GetMoreSyncResult cut = sync();

        assertThat(cut.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(voucher.getSyncBlockedBy()).isEqualTo(VoucherSyncIssueKind.TOO_MANY_ORDERS);
        assertThat(issues).extracting(VoucherSyncIssue::getKind, VoucherSyncIssue::isOpen).containsExactlyInAnyOrder(
                tuple(VoucherSyncIssueKind.CURRENCY_MISMATCH, true), tuple(VoucherSyncIssueKind.TOO_MANY_ORDERS, true));
    }

    // ---- fakes --------------------------------------------------------------------------------------------------

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static GetMoreCoupon coupon(long id, String code, int timesUsed) {
        return new GetMoreCoupon(id, 43, code, 0, 0, timesUsed, null, false, TestVouchers.GETMORE_ISSUED_AT, 1);
    }

    private static GetMoreOrder order(long id, String ordered, String invoiced, LocalDateTime at) {
        return order(id, ordered, invoiced, "0", at, "USD");
    }

    private static GetMoreOrder order(long id, String ordered, String invoiced, LocalDateTime at, String currency) {
        return order(id, ordered, invoiced, "0", at, currency);
    }

    private static GetMoreOrder order(long id, String ordered, String invoiced, String refunded, LocalDateTime at) {
        return order(id, ordered, invoiced, refunded, at, "USD");
    }

    private static GetMoreOrder order(long id, String ordered, String invoiced, String refunded, LocalDateTime at,
                                      String currency) {
        return new GetMoreOrder(id, String.format("SIM%09d", id), CODE, Set.of(43L), "complete", currency,
                new BigDecimal(ordered), BigDecimal.ZERO, new BigDecimal(ordered).negate(),
                new BigDecimal(invoiced).negate(), new BigDecimal(refunded).negate(), BigDecimal.ZERO, 2,
                "GetMore\nGetMore Avondale\nDefault Store View", at, at);
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        void set(Instant to) {
            now = to;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** An in-memory Magento for one prefix: refuses a call with a transaction open, records the calls that matter. */
    static final class FakeStore implements GetMoreStore {
        final List<GetMoreCoupon> coupons = new CopyOnWriteArrayList<>();
        /** Coupons a GET by id finds that the search does not (a lagging search). */
        final List<GetMoreCoupon> byId = new CopyOnWriteArrayList<>();
        final List<GetMoreOrder> orders = new CopyOnWriteArrayList<>();
        final List<GetMoreCreditMemo> memos = new CopyOnWriteArrayList<>();
        final List<String> deleted = new CopyOnWriteArrayList<>();
        final List<Long> lookedUp = new CopyOnWriteArrayList<>();
        /** The expiry dates PUT on the voucher's coupon, in order. */
        final List<LocalDateTime> expiriesSet = new CopyOnWriteArrayList<>();
        volatile RuntimeException updateFailure;
        final List<String> events;
        volatile RuntimeException orderFailure;
        volatile Runnable onSearch;
        volatile Integer orderTotalOverride;

        FakeStore(List<String> events) {
            this.events = events;
        }

        @Override
        public GetMoreMode mode() {
            return GetMoreMode.SIMULATED;
        }

        @Override
        public List<String> generateCoupons(GetMoreCouponSpec spec) {
            throw new AssertionError("a sync never generates");
        }

        @Override
        public GetMorePage<GetMoreCoupon> searchCoupons(GetMoreCouponQuery query) {
            GetMoreCalls.requireNoTransaction();
            events.add("search");
            Runnable hook = onSearch;
            if (hook != null) {
                hook.run();
            }
            List<GetMoreCoupon> matching = coupons.stream()
                    .filter(c -> GetMoreCodes.hasPrefix(c.code(), query.prefix())).toList();
            return new GetMorePage<>(matching, matching.size(), query.page(), query.pageSize());
        }

        @Override
        public GetMoreCoupon coupon(long couponId) {
            GetMoreCalls.requireNoTransaction();
            lookedUp.add(couponId);
            return byId.stream().filter(c -> c.couponId() == couponId).findFirst()
                    .orElseThrow(() -> new GetMoreNotFoundException(Operation.GET_COUPON, "No such entity."));
        }

        @Override
        public GetMoreCoupon updateCoupon(long couponId, GetMoreCouponUpdate update) {
            GetMoreCalls.requireNoTransaction();
            if (updateFailure != null) {
                throw updateFailure;
            }
            GetMoreCoupon coupon = coupons.stream().filter(c -> c.couponId() == couponId).findFirst().orElseThrow();
            assertThat(update.code()).isEqualTo(coupon.code());
            assertThat(update.ruleId()).isEqualTo(coupon.ruleId());
            expiriesSet.add(update.expirationDateUtc());
            return coupon;
        }

        @Override
        public GetMoreCouponDeletion deleteCoupons(List<String> codesAsStored, boolean ignoreInvalidCoupons) {
            GetMoreCalls.requireNoTransaction();
            List<String> missing = new ArrayList<>();
            for (String code : codesAsStored) {
                if (coupons.removeIf(c -> c.code().equals(code))) {
                    deleted.add(code);
                } else {
                    missing.add(code);
                }
            }
            return new GetMoreCouponDeletion(List.of(), missing);
        }

        @Override
        public GetMoreSalesRule salesRule(long ruleId) {
            throw new AssertionError("a sync never reads the rule");
        }

        @Override
        public GetMorePage<GetMoreOrder> orders(GetMoreOrderQuery query) {
            GetMoreCalls.requireNoTransaction();
            if (orderFailure != null) {
                throw orderFailure;
            }
            List<GetMoreOrder> matching = orders.stream()
                    .filter(o -> GetMoreCodes.hasPrefix(o.couponCode(), query.couponPrefix())).toList();
            int total = orderTotalOverride == null ? matching.size() : orderTotalOverride;
            return new GetMorePage<>(query.page() == 1 ? matching : List.of(), total, query.page(), query.pageSize());
        }

        @Override
        public GetMorePage<GetMoreCreditMemo> creditMemos(GetMoreCreditMemoQuery query) {
            GetMoreCalls.requireNoTransaction();
            Collection<Long> ids = query.orderIds();
            List<GetMoreCreditMemo> matching = memos.stream().filter(m -> ids.contains(m.orderId())).toList();
            return new GetMorePage<>(matching, matching.size(), query.page(), query.pageSize());
        }
    }
}
