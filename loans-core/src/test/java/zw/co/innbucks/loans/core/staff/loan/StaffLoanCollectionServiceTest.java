package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.merchant.VoucherStore;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.ExclusionReason;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.Line;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanCollectionReport.SettlementLine;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncTrigger;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherSync;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherCodeSource;
import zw.co.innbucks.loans.core.voucher.VoucherRedemption;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionRepository;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionSource;
import zw.co.innbucks.loans.core.voucher.VoucherRefund;
import zw.co.innbucks.loans.core.voucher.VoucherRefundRepository;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.core.voucher.VoucherStopReason;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The payday statement for Friday 20 November 2026 (cut-off 00:00 in Harare, 22:00 UTC on the 19th): which loans it
 * lists and what each owes, which GetMore rows the cycle pays for, the payday sync's reach, finalising it and what it
 * refuses, and a row recorded after finalisation landing in the next payday. Every timestamp is UTC, so it passes
 * under any JVM zone; run it under TZ=Africa/Harare too.
 */
class StaffLoanCollectionServiceTest {

    private static final LocalDate PAYDAY = LocalDate.of(2026, 11, 20);
    private static final LocalDate PREVIOUS = LocalDate.of(2026, 10, 20);
    private static final LocalDate NEXT = LocalDate.of(2026, 12, 20);
    /** 00:00 on 20 November in Harare. */
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 19, 22, 0);
    private static final LocalDateTime PREVIOUS_CUTOFF = LocalDateTime.of(2026, 10, 19, 22, 0);
    private static final Instant PAYDAY_MORNING = Instant.parse("2026-11-20T06:02:11Z");

    private final List<StaffLoan> loans = new ArrayList<>();
    private final List<Voucher> vouchers = new ArrayList<>();
    private final List<VoucherRedemption> spends = new ArrayList<>();
    private final List<VoucherRefund> refunds = new ArrayList<>();
    private final Map<StaffLoanCollectionCycle.Key, StaffLoanCollectionCycle> cycles = new TreeMap<>(
            Comparator.comparing(StaffLoanCollectionCycle.Key::getPayday)
                    .thenComparing(StaffLoanCollectionCycle.Key::getStoreMode));
    private final List<StaffLoanCollectionLine> lines = new ArrayList<>();
    private final List<StaffLoanVoucherIssuance> cancelling = new ArrayList<>();
    private final List<StaffLoanVoucherIssuance> issuances = new ArrayList<>();
    private final List<Long> synced = new ArrayList<>();
    /** Run once, right after the next read of GetMore rows: a sync committing a row between two reads. */
    private final AtomicReference<Runnable> afterNextRowRead = new AtomicReference<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(PAYDAY_MORNING);
    private final GetMoreProperties getMore = new GetMoreProperties();
    private final Merchant store = merchant();
    private StaffLoanCollectionLineRepository lineRepository;
    private StaffLoanCollectionCycleRepository cycleRepository;
    private VoucherRedemptionRepository redemptionRepository;
    private GetMoreVoucherSync sync;
    private StaffLoanVoucherIssuer issuer;
    private AuditService auditService;
    private StaffLoanCollectionService service;

    /** The clock the service reads, set by the test. */
    private final class TestClock extends Clock {
        private final ZoneId zone;

        TestClock(ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return new TestClock(other);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private static Merchant merchant() {
        Merchant merchant = Merchant.builder().merchantCode("getmore-groceries").companyName("GetMore Groceries")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).voucherStore(VoucherStore.GETMORE).build();
        merchant.setId(3L);
        return merchant;
    }

    @BeforeEach
    void setUp() {
        getMore.setMode(GetMoreMode.LIVE);
        StaffLoanRepository loanRepository = mock(StaffLoanRepository.class);
        when(loanRepository.findDueOn(any(), any())).thenAnswer(i -> {
            LocalDate due = i.getArgument(0);
            Collection<StaffLoanStatus> statuses = i.getArgument(1);
            return loans.stream().filter(loan -> loan.getDueDate().equals(due) && statuses.contains(loan.getStatus()))
                    .sorted(Comparator.comparing(StaffLoan::getId)).toList();
        });
        when(loanRepository.findAllById(any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            return loans.stream().filter(loan -> ids.contains(loan.getId())).toList();
        });
        VoucherRepository voucherRepository = mock(VoucherRepository.class);
        when(voucherRepository.findByStaffLoanIdIn(any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            return vouchers.stream().filter(voucher -> ids.contains(voucher.getStaffLoanId())).toList();
        });
        when(voucherRepository.findAllById(any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            return vouchers.stream().filter(voucher -> ids.contains(voucher.getId())).toList();
        });
        when(voucherRepository.findGetMoreIdsForCycle(any(), any(), any(), anyLong(), anyInt())).thenAnswer(i ->
                covered(i.getArgument(0), i.getArgument(1), i.getArgument(2)).stream()
                        .filter(voucher -> voucher.getId() > (long) i.getArgument(3)).map(Voucher::getId).sorted()
                        .limit((int) i.getArgument(4)).toList());
        when(voucherRepository.countGetMoreStaleForCycle(any(), any(), any(), any())).thenAnswer(i -> {
            LocalDateTime syncedBefore = i.getArgument(3);
            return covered(i.getArgument(0), i.getArgument(1), i.getArgument(2)).stream()
                    .filter(voucher -> voucher.getSyncedAt() == null || voucher.getSyncedAt().isBefore(syncedBefore))
                    .count();
        });
        redemptionRepository = mock(VoucherRedemptionRepository.class);
        when(redemptionRepository.findByVoucherIdInOrderByIdAsc(any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            return afterRead(spends.stream().filter(spend -> ids.contains(spend.getVoucherId())).toList());
        });
        when(redemptionRepository.findUnsettledBefore(any(), any())).thenAnswer(i -> afterRead(spends.stream()
                .filter(spend -> spend.getSource() == VoucherRedemptionSource.GETMORE_SYNC
                        && spend.getStoreMode() == i.getArgument(0) && spend.getSettlementCycle() == null
                        && spend.getRedeemedAt().isBefore(i.getArgument(1))).toList()));
        when(redemptionRepository.findBySettlementCycleAndStoreModeOrderByIdAsc(any(), any())).thenAnswer(i ->
                spends.stream().filter(spend -> i.getArgument(0).equals(spend.getSettlementCycle())
                        && spend.getStoreMode() == i.getArgument(1)).toList());
        when(redemptionRepository.stampSettlementCycle(any(), any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            int stamped = 0;
            for (VoucherRedemption spend : spends) {
                if (ids.contains(spend.getId()) && spend.getSettlementCycle() == null) {
                    ReflectionTestUtils.setField(spend, "settlementCycle", i.getArgument(1));
                    stamped++;
                }
            }
            return stamped;
        });
        VoucherRefundRepository refundRepository = mock(VoucherRefundRepository.class);
        when(refundRepository.findByVoucherIdInOrderByIdAsc(any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            return refunds.stream().filter(refund -> ids.contains(refund.getVoucherId())).toList();
        });
        when(refundRepository.findUnsettledBefore(any(), any())).thenAnswer(i -> refunds.stream()
                .filter(refund -> refund.getStoreMode() == i.getArgument(0) && refund.getSettlementCycle() == null
                        && refund.getRefundedAt().isBefore(i.getArgument(1))).toList());
        when(refundRepository.findBySettlementCycleAndStoreModeOrderByIdAsc(any(), any())).thenAnswer(i ->
                refunds.stream().filter(refund -> i.getArgument(0).equals(refund.getSettlementCycle())
                        && refund.getStoreMode() == i.getArgument(1)).toList());
        when(refundRepository.stampSettlementCycle(any(), any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            int stamped = 0;
            for (VoucherRefund refund : refunds) {
                if (ids.contains(refund.getId()) && refund.getSettlementCycle() == null) {
                    ReflectionTestUtils.setField(refund, "settlementCycle", i.getArgument(1));
                    stamped++;
                }
            }
            return stamped;
        });
        VoucherSyncIssueRepository issueRepository = mock(VoucherSyncIssueRepository.class);
        cycleRepository = mock(StaffLoanCollectionCycleRepository.class);
        when(cycleRepository.find(any(), any())).thenAnswer(i -> Optional.ofNullable(cycles.get(
                new StaffLoanCollectionCycle.Key(i.getArgument(0), i.getArgument(1)))));
        when(cycleRepository.findFirstByStoreModeAndPaydayAfterOrderByPaydayAsc(any(), any())).thenAnswer(i ->
                cycles.values().stream().filter(cycle -> cycle.getStoreMode() == i.getArgument(0)
                        && cycle.getPayday().isAfter(i.getArgument(1))).findFirst());
        when(cycleRepository.saveAndFlush(any())).thenAnswer(i -> {
            StaffLoanCollectionCycle cycle = i.getArgument(0);
            if (cycles.putIfAbsent(cycle.getId(), cycle) != null) {
                throw new DataIntegrityViolationException("staff_loan_collection_cycles_pkey");
            }
            return cycle;
        });
        lineRepository = mock(StaffLoanCollectionLineRepository.class);
        when(lineRepository.saveAll(any())).thenAnswer(i -> {
            Iterable<StaffLoanCollectionLine> saved = i.getArgument(0);
            saved.forEach(lines::add);
            return saved;
        });
        when(lineRepository.findByPaydayAndStoreModeOrderByIdAsc(any(), any())).thenAnswer(i -> lines.stream()
                .filter(line -> line.getPayday().equals(i.getArgument(0)) && line.getStoreMode() == i.getArgument(1))
                .toList());
        StaffLoanVoucherIssuanceRepository issuanceRepository = mock(StaffLoanVoucherIssuanceRepository.class);
        when(issuanceRepository.findByStatusAndStoreModeOrderByIdAsc(any(), any())).thenAnswer(i -> cancelling);
        when(issuanceRepository.findByStaffLoanIdIn(any())).thenAnswer(i -> {
            Collection<Long> ids = i.getArgument(0);
            return issuances.stream().filter(issuance -> ids.contains(issuance.getStaffLoanId())).toList();
        });
        MerchantRepository merchantRepository = mock(MerchantRepository.class);
        when(merchantRepository.findByStaffLoanMerchantTrue()).thenReturn(Optional.of(store));
        sync = mock(GetMoreVoucherSync.class);
        when(sync.sync(anyLong(), eq(GetMoreSyncTrigger.PAYDAY))).thenAnswer(i -> {
            synced.add(i.getArgument(0));
            return new GetMoreSyncResult(GetMoreSyncResult.Outcome.UNCHANGED, 0, 0, null, 0, List.of(), false, null);
        });
        issuer = mock(StaffLoanVoucherIssuer.class);
        when(issuer.cleanup(anyLong())).thenReturn(StaffLoanVoucherIssuer.Cleanup.WITHDRAWN);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("finance1");
        auditService = mock(AuditService.class);
        service = new StaffLoanCollectionService(loanRepository, voucherRepository, redemptionRepository,
                refundRepository, issueRepository, cycleRepository, lineRepository, issuanceRepository,
                merchantRepository, sync, issuer, new StaffLoanProperties(), getMore, authService, auditService,
                new MarketTimeZone("ZW", new TestClock(ZoneOffset.UTC)));
    }

    /** {@code read}, after running what was waiting for the next read of GetMore rows, if anything. */
    private <T> List<T> afterRead(List<T> read) {
        Runnable then = afterNextRowRead.getAndSet(null);
        if (then != null) {
            then.run();
        }
        return read;
    }

    /** What the payday sync walks, as the repository's query has it. */
    private List<Voucher> covered(GetMoreMode mode, LocalDateTime issuedBefore, LocalDateTime expiresFrom) {
        return vouchers.stream().filter(voucher -> voucher.getCodeSource() == VoucherCodeSource.GETMORE
                && voucher.getStoreMode() == mode && voucher.getIssuedAt().isBefore(issuedBefore)
                && !voucher.getExpiresAt().isBefore(expiresFrom)).toList();
    }

    // ---- the story ---------------------------------------------------------------------------------------------------

    private StaffLoan loan(long id, String employee, StaffLoanStatus status, UnredeemedVoucherTreatment treatment,
                           LocalDate due, String reference) {
        StaffLoan loan = StaffLoan.builder().id(id).reference(String.format("SGL-2026-%06d", id)).staffMemberId(id)
                .employeeNumber(employee).fullName("Borrower " + employee).merchant(store).status(status)
                .unredeemedVoucherTreatment(treatment).amount(new BigDecimal("150.00"))
                .totalRepayable(new BigDecimal("150.00")).currency("USD").dueDate(due)
                .disbursedAt(reference == null ? null : LocalDateTime.of(2026, 10, 14, 8, 20, 7))
                .disbursementReference(reference).build();
        loans.add(loan);
        return loan;
    }

    private Voucher voucher(long id, StaffLoan loan, GetMoreMode mode, LocalDateTime issued, LocalDateTime syncedAt) {
        Voucher voucher = Voucher.builder().id(id).merchant(store).loanAccount(loan.getReference())
                .faceValue(new BigDecimal("150.00")).redeemedAmount(new BigDecimal("0.00")).currency("USD")
                .issuedAt(issued).expiresAt(issued.plusDays(31).withHour(21).withMinute(59).withSecond(59))
                .status(VoucherStatus.ISSUED).codeSource(VoucherCodeSource.GETMORE).storeMode(mode)
                .staffLoanId(loan.getId()).heldAmount(new BigDecimal("0.00")).syncedAt(syncedAt).build();
        vouchers.add(voucher);
        return voucher;
    }

    /** A sale of GetMore order 12, the order the story's refunds are against. */
    private VoucherRedemption spend(long id, Voucher voucher, String amount, LocalDateTime at) {
        return spend(id, voucher, 12L, amount, at);
    }

    private VoucherRedemption spend(long id, Voucher voucher, long order, String amount, LocalDateTime at) {
        VoucherRedemption spend = VoucherRedemption.builder().id(id).voucherId(voucher.getId())
                .merchantId(store.getId()).merchantReference("GM:O:" + order + ":" + id)
                .amount(new BigDecimal(amount)).source(VoucherRedemptionSource.GETMORE_SYNC)
                .storeMode(voucher.getStoreMode()).externalOrderId(order).redeemedAt(at)
                .recordedAt(at.plusMinutes(2)).build();
        spends.add(spend);
        return spend;
    }

    private VoucherRefund refund(long id, Voucher voucher, String amount, LocalDateTime at) {
        VoucherRefund refund = VoucherRefund.builder().id(id).voucherId(voucher.getId()).merchantId(store.getId())
                .merchantReference("GM:CM:" + id).storeMode(voucher.getStoreMode()).externalOrderId(12L)
                .amount(new BigDecimal(amount)).refundedAt(at).recordedAt(at.plusMinutes(2)).build();
        refunds.add(refund);
        return refund;
    }

    private static LocalDateTime utc(String instant) {
        return LocalDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
    }

    /** Rutendo's SGL-2026-000164, debt stands, voucher 9 spent 100.00 and refunded 10.00, synced on payday. */
    private Voucher rutendo() {
        StaffLoan loan = loan(164, "E1064", StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.DEBT_STANDS,
                PAYDAY, "GETMORE-1207");
        Voucher voucher = voucher(9, loan, GetMoreMode.LIVE, utc("2026-10-14T08:20:07Z"),
                utc("2026-11-20T06:00:00Z"));
        spend(44, voucher, "45.50", utc("2026-10-14T08:31:02Z"));
        spend(45, voucher, "54.50", utc("2026-10-28T08:35:40Z"));
        refund(3, voucher, "10.00", utc("2026-11-02T08:39:58Z"));
        return voucher;
    }

    private static Line line(StaffLoanCollectionReport report, String reference) {
        return report.lines().stream().filter(line -> line.loanReference().equals(reference)).findFirst()
                .orElseThrow();
    }

    // ---- the payday ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a payday is a repayment day: the 19th is refused by the statement, the sync and the finalisation")
    void paydayValidation() {
        LocalDate thursday = LocalDate.of(2026, 11, 19);
        String message = "2026-11-19 is not a payday: Staff Grocery Loans are collected on day 20 of each month";

        assertThatThrownBy(() -> service.report(thursday)).isInstanceOf(ValidationException.class)
                .hasMessage(message);
        assertThatThrownBy(() -> service.csv(thursday)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.syncForPayday(thursday, 0L, 50)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.finalise(thursday, null)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(sync);
    }

    @Test
    @DisplayName("the debt stands: the whole repayment, final once read since the cut-off; GetMore is paid 90.00")
    void debtStands() {
        rutendo();

        StaffLoanCollectionReport report = service.report(PAYDAY);

        assertThat(report.cutoff()).isEqualTo(CUTOFF);
        assertThat(report.storeMode()).isEqualTo(GetMoreMode.LIVE);
        assertThat(report.simulated()).isFalse();
        assertThat(report.finalised()).isFalse();
        Line line = line(report, "SGL-2026-000164");
        assertThat(line.voucherStateAtCutoff()).isEqualTo(VoucherStatus.EXPIRED);
        assertThat(line.spentNet()).isEqualByComparingTo("90.00");
        assertThat(line.refunded()).isEqualByComparingTo("10.00");
        assertThat(line.amountToCollect()).isEqualByComparingTo("150.00");
        assertThat(line.finalAmount()).isTrue();
        assertThat(line.flags()).isEmpty();
        assertThat(report.totals()).singleElement().satisfies(totals -> {
            assertThat(totals.loans()).isEqualTo(1);
            assertThat(totals.amountToCollect()).isEqualByComparingTo("150.00");
            assertThat(totals.provisionalLoans()).isZero();
        });
        assertThat(report.merchantSettlement().merchantCode()).isEqualTo("getmore-groceries");
        assertThat(report.merchantSettlement().totals()).singleElement().satisfies(totals -> {
            assertThat(totals.spends()).isEqualByComparingTo("100.00");
            assertThat(totals.refunds()).isEqualByComparingTo("10.00");
            assertThat(totals.net()).isEqualByComparingTo("90.00");
        });
        assertThat(report.merchantSettlement().lines()).extracting(SettlementLine::voucherId, SettlementLine::late)
                .containsExactly(tuple(9L, false));
        assertThat(report.staleLines()).isZero();
    }

    @Test
    @DisplayName("the cut-off is 00:00 in Harare: a sale at 23:59:59 on the 19th counts, one at 00:00 on the 20th not")
    void cutoffInMarketTime() {
        StaffLoan loan = loan(170, "E1070", StaffLoanStatus.DISBURSED,
                UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT, PAYDAY, "GETMORE-1300");
        Voucher voucher = voucher(20, loan, GetMoreMode.LIVE, utc("2026-10-25T08:00:00Z"),
                utc("2026-11-20T06:00:00Z"));
        spend(60, voucher, "30.00", utc("2026-11-19T21:59:59Z"));
        spend(61, voucher, "25.00", utc("2026-11-19T22:00:00Z"));

        StaffLoanCollectionReport report = service.report(PAYDAY);

        Line line = line(report, "SGL-2026-000170");
        assertThat(line.spentNet()).isEqualByComparingTo("30.00");
        assertThat(line.voucherStateAtCutoff()).isEqualTo(VoucherStatus.PARTIALLY_REDEEMED);
        assertThat(line.amountToCollect()).isEqualByComparingTo("30.00");
        assertThat(line.finalAmount()).as("still open at the cut-off, reduced to what is spent").isFalse();
        assertThat(line.couldRiseTo()).isEqualByComparingTo("150.00");
        assertThat(line.openBalance()).isEqualByComparingTo("120.00");
        assertThat(line.flags()).containsExactly(StaffLoanOwed.Flag.VOUCHER_OPEN);
        assertThat(report.merchantSettlement().totals().getFirst().spends()).isEqualByComparingTo("30.00");
        assertThat(report.totals().getFirst().provisionalAmount()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("the preview pays GetMore for unsettled rows only; a row an earlier payday paid stays in what was spent")
    void previewTakesUnsettledRows() {
        Voucher voucher = rutendo();
        ReflectionTestUtils.setField(spends.getFirst(), "settlementCycle", PREVIOUS);

        StaffLoanCollectionReport report = service.report(PAYDAY);

        assertThat(line(report, "SGL-2026-000164").spentNet()).isEqualByComparingTo("90.00");
        assertThat(report.merchantSettlement().totals().getFirst().spends()).isEqualByComparingTo("54.50");
        assertThat(report.merchantSettlement().totals().getFirst().net()).isEqualByComparingTo("44.50");
        assertThat(voucher.getId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("not read since the cut-off: provisional and stale, and finalising it needs acceptNonFinal and why")
    void staleRefusedUnlessAccepted() {
        Voucher voucher = rutendo();
        ReflectionTestUtils.setField(voucher, "syncedAt", utc("2026-11-02T08:40:00Z"));

        StaffLoanCollectionReport report = service.report(PAYDAY);
        assertThat(line(report, "SGL-2026-000164").flags()).containsExactly(StaffLoanOwed.Flag.STALE);
        assertThat(line(report, "SGL-2026-000164").finalAmount()).isFalse();
        assertThat(report.staleLines()).isEqualTo(1);
        assertThat(report.merchantSettlement().staleVouchers()).isEqualTo(1);

        assertThatThrownBy(() -> service.finalise(PAYDAY, null)).isInstanceOf(ConflictException.class)
                .hasMessage("1 line is not final and 1 voucher is stale: sync first (POST /lending/v1/"
                        + "staff-loan-collections/2026-11-20/getmore-syncs) or finalise with acceptNonFinal and a"
                        + " reason");
        assertThatThrownBy(() -> service.finalise(PAYDAY, new FinaliseCollectionRequest(true, "  ")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("reason is required to finalise with acceptNonFinal");
        assertThat(cycles).isEmpty();

        StaffLoanCollectionReport finalised = service.finalise(PAYDAY, new FinaliseCollectionRequest(true,
                "GetMore was down before the payroll run"));

        assertThat(finalised.finalised()).isTrue();
        assertThat(finalised.finalisationReason()).isEqualTo("GetMore was down before the payroll run");
        StaffLoanCollectionCycle cycle = cycles.values().iterator().next();
        assertThat(cycle.getNonFinalLines()).isEqualTo(1);
        assertThat(cycle.getStaleVouchers()).isEqualTo(1);
        assertThat(lines).singleElement().satisfies(kept -> {
            assertThat(kept.isFinalAmount()).isFalse();
            assertThat(kept.getFlags()).isEqualTo("STALE");
        });
    }

    @Test
    @DisplayName("finalising keeps the lines, stamps the rows it pays for, audits, and is refused a second time")
    void finalise() {
        rutendo();

        StaffLoanCollectionReport finalised = service.finalise(PAYDAY, null);

        InOrder first = inOrder(cycleRepository, redemptionRepository);
        first.verify(cycleRepository).lockStore("staff-loan-collections:LIVE");
        first.verify(cycleRepository).find(PAYDAY, GetMoreMode.LIVE);
        first.verify(redemptionRepository).findUnsettledBefore(GetMoreMode.LIVE, CUTOFF);
        first.verify(redemptionRepository).findByVoucherIdInOrderByIdAsc(List.of(9L));
        assertThat(finalised.finalised()).isTrue();
        assertThat(finalised.finalisedAt()).isEqualTo(utc("2026-11-20T06:02:11Z"));
        assertThat(finalised.finalisedBy()).isEqualTo("finance1");
        assertThat(StaffLoanCollectionService.finalisedMessage(finalised)).isEqualTo("Collections for 20 Nov 2026"
                + " finalised: 1 loan, USD 150.00 to collect; USD 90.00 to pay GetMore Groceries");
        assertThat(lines).singleElement().satisfies(kept -> {
            assertThat(kept.getStaffLoanId()).isEqualTo(164L);
            assertThat(kept.getAmountToCollect()).isEqualByComparingTo("150.00");
            assertThat(kept.isFinalAmount()).isTrue();
            assertThat(kept.getFlags()).isEmpty();
            assertThat(kept.getStoreMode()).isEqualTo(GetMoreMode.LIVE);
        });
        assertThat(spends).allSatisfy(spend -> assertThat(spend.getSettlementCycle()).isEqualTo(PAYDAY));
        assertThat(refunds).allSatisfy(refund -> assertThat(refund.getSettlementCycle()).isEqualTo(PAYDAY));
        assertThat(finalised.merchantSettlement().totals().getFirst().net()).isEqualByComparingTo("90.00");
        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        AuditLog row = audit.getValue().build();
        assertThat(row.getEventType()).isEqualTo("STAFF_LOAN_COLLECTION_FINALISED");
        assertThat(row.getEntityId()).isEqualTo("2026-11-20:LIVE");
        assertThat(row.getActorId()).isEqualTo("finance1");
        assertThat(row.getDetail()).contains("lines:1", "collect:USD 150.00", "settle:USD 90.00", "spends:2",
                "refunds:1");

        assertThatThrownBy(() -> service.finalise(PAYDAY, null)).isInstanceOf(ConflictException.class)
                .hasMessage("Payday 20 Nov 2026 is already finalised");
        assertThat(service.report(PAYDAY).lines()).singleElement()
                .satisfies(line -> assertThat(line.amountToCollect()).isEqualByComparingTo("150.00"));
    }

    @Test
    @DisplayName("refused before the cut-off, and once a later payday is finalised")
    void finaliseOutOfTurn() {
        rutendo();
        now.set(Instant.parse("2026-11-19T21:59:59Z"));
        assertThatThrownBy(() -> service.finalise(PAYDAY, null)).isInstanceOf(ConflictException.class)
                .hasMessage("The cut-off has not passed yet: payday 20 Nov 2026 can be finalised from 00:00 on that"
                        + " day");

        now.set(Instant.parse("2026-12-20T06:00:00Z"));
        service.finalise(NEXT, new FinaliseCollectionRequest(true, "voucher 9 last read on 20 November"));
        assertThatThrownBy(() -> service.finalise(PAYDAY, null)).isInstanceOf(ConflictException.class)
                .hasMessage("A later payday, 20 Dec 2026, is already finalised: 20 Nov 2026 can no longer be");
        assertThat(cycles).hasSize(1);
    }

    @Test
    @DisplayName("a row made before the cut-off but recorded after finalisation: late in the next payday, and listed")
    void lateRowLandsInTheNextCycle() {
        Voucher voucher = rutendo();
        service.finalise(PAYDAY, null);

        now.set(Instant.parse("2026-11-21T07:14:00Z"));
        refund(4, voucher, "5.00", utc("2026-11-19T14:12:00Z"));

        StaffLoanCollectionReport november = service.report(PAYDAY);
        assertThat(november.lines()).singleElement().satisfies(line -> {
            assertThat(line.amountToCollect()).as("as sent to payroll").isEqualByComparingTo("150.00");
            assertThat(line.spentNet()).as("as known when finalised").isEqualByComparingTo("90.00");
        });
        assertThat(november.lateAdjustments()).singleElement().satisfies(late -> {
            assertThat(late.loanReference()).isEqualTo("SGL-2026-000164");
            assertThat(late.refunds()).isEqualByComparingTo("5.00");
            assertThat(late.net()).isEqualByComparingTo("-5.00");
        });
        assertThat(november.merchantSettlement().totals().getFirst().net()).as("what was paid")
                .isEqualByComparingTo("90.00");

        StaffLoanCollectionReport december = service.report(NEXT);
        assertThat(december.merchantSettlement().previousCycle().payday()).isEqualTo(PAYDAY);
        assertThat(december.merchantSettlement().lines()).singleElement().satisfies(line -> {
            assertThat(line.voucherId()).isEqualTo(9L);
            assertThat(line.late()).isTrue();
            assertThat(line.net()).isEqualByComparingTo("-5.00");
        });
        assertThat(december.merchantSettlement().totals().getFirst().net()).as("never clamped")
                .isEqualByComparingTo("-5.00");
    }

    @Test
    @DisplayName("a written-off loan is excluded with what it owes; a simulated one, whatever its status, on LIVE")
    void exclusionsOnALiveHost() {
        rutendo();
        loan(171, "E1071", StaffLoanStatus.WRITTEN_OFF, UnredeemedVoucherTreatment.DEBT_STANDS, PAYDAY,
                "GETMORE-1301");
        StaffLoan simulated = loan(172, "E1072", StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.DEBT_STANDS,
                PAYDAY, "GETMORE-SIM-1302");
        Voucher test = voucher(22, simulated, GetMoreMode.SIMULATED, utc("2026-10-20T08:00:00Z"),
                utc("2026-11-20T06:00:00Z"));
        spend(70, test, "40.00", utc("2026-10-21T08:00:00Z"));
        // A staging test loan written off, and one still awaiting its simulated voucher, after the host left SIMULATED.
        loan(173, "E1073", StaffLoanStatus.WRITTEN_OFF, UnredeemedVoucherTreatment.DEBT_STANDS, PAYDAY,
                "GETMORE-SIM-1303");
        StaffLoan awaiting = loan(174, "E1074", StaffLoanStatus.AWAITING_DISBURSEMENT,
                UnredeemedVoucherTreatment.DEBT_STANDS, PAYDAY, null);
        issuances.add(StaffLoanVoucherIssuance.builder().id(78L).staffLoanId(awaiting.getId())
                .storeMode(GetMoreMode.SIMULATED).status(StaffLoanVoucherIssuanceStatus.PENDING).build());

        StaffLoanCollectionReport report = service.report(PAYDAY);

        assertThat(report.lines()).extracting(Line::loanReference).containsExactly("SGL-2026-000164");
        assertThat(report.excluded()).extracting(StaffLoanCollectionReport.Excluded::loanReference,
                        StaffLoanCollectionReport.Excluded::reason)
                .containsExactly(tuple("SGL-2026-000171", ExclusionReason.WRITTEN_OFF),
                        tuple("SGL-2026-000172", ExclusionReason.SIMULATED),
                        tuple("SGL-2026-000173", ExclusionReason.SIMULATED),
                        tuple("SGL-2026-000174", ExclusionReason.SIMULATED));
        assertThat(report.excluded().getFirst().outstanding()).isEqualByComparingTo("150.00");
        assertThat(report.totals().getFirst().amountToCollect()).isEqualByComparingTo("150.00");
        assertThat(report.merchantSettlement().lines()).extracting(SettlementLine::voucherId).containsExactly(9L);
    }

    @Test
    @DisplayName("on the simulated host: labelled, a -SIMULATED file, and finalising settles the simulated rows only")
    void simulatedHost() {
        getMore.setMode(GetMoreMode.SIMULATED);
        Voucher live = rutendo();
        StaffLoan loan = loan(172, "E1072", StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.DEBT_STANDS,
                PAYDAY, "GETMORE-SIM-1302");
        Voucher test = voucher(22, loan, GetMoreMode.SIMULATED, utc("2026-10-20T08:00:00Z"),
                utc("2026-11-20T06:00:00Z"));
        spend(70, test, "40.00", utc("2026-10-21T08:00:00Z"));

        StaffLoan awaiting = loan(174, "E1074", StaffLoanStatus.AWAITING_DISBURSEMENT,
                UnredeemedVoucherTreatment.DEBT_STANDS, PAYDAY, null);
        issuances.add(StaffLoanVoucherIssuance.builder().id(78L).staffLoanId(awaiting.getId())
                .storeMode(GetMoreMode.SIMULATED).status(StaffLoanVoucherIssuanceStatus.PENDING).build());
        StaffLoan liveAwaiting = loan(178, "E1078", StaffLoanStatus.AWAITING_DISBURSEMENT,
                UnredeemedVoucherTreatment.DEBT_STANDS, PAYDAY, null);
        issuances.add(StaffLoanVoucherIssuance.builder().id(79L).staffLoanId(liveAwaiting.getId())
                .storeMode(GetMoreMode.LIVE).status(StaffLoanVoucherIssuanceStatus.PENDING).build());

        StaffLoanCollectionReport report = service.report(PAYDAY);

        assertThat(report.simulated()).isTrue();
        assertThat(report.storeMode()).isEqualTo(GetMoreMode.SIMULATED);
        assertThat(line(report, "SGL-2026-000172").simulated()).isTrue();
        assertThat(line(report, "SGL-2026-000174").simulated()).as("being issued a simulated coupon").isTrue();
        assertThat(report.lines()).extracting(Line::loanReference)
                .containsExactly("SGL-2026-000172", "SGL-2026-000174");
        assertThat(report.excluded()).as("the LIVE store's loans are not on the simulated statement")
                .extracting(StaffLoanCollectionReport.Excluded::loanReference,
                        StaffLoanCollectionReport.Excluded::reason)
                .containsExactly(tuple("SGL-2026-000164", ExclusionReason.LIVE),
                        tuple("SGL-2026-000178", ExclusionReason.LIVE));
        assertThat(report.merchantSettlement().lines()).extracting(SettlementLine::voucherId).containsExactly(22L);
        assertThat(service.csv(PAYDAY).fileName()).isEqualTo("staff-loan-collections-2026-11-20-SIMULATED.csv");
        assertThat(StaffLoanCollectionService.finalisedMessage(service.finalise(PAYDAY, null)))
                .endsWith("(SIMULATED: not for payroll)");
        assertThat(spends).filteredOn(spend -> spend.getVoucherId().equals(live.getId()))
                .allSatisfy(spend -> assertThat(spend.getSettlementCycle()).isNull());
        assertThat(spends).filteredOn(spend -> spend.getVoucherId().equals(22L))
                .allSatisfy(spend -> assertThat(spend.getSettlementCycle()).isEqualTo(PAYDAY));
        assertThat(cycles.keySet()).extracting(StaffLoanCollectionCycle.Key::getStoreMode)
                .containsExactly(GetMoreMode.SIMULATED);
    }

    @Test
    @DisplayName("the payday sync reaches a voucher of a loan due next month that was spent this cycle")
    void paydaySyncCoverage() {
        rutendo();
        StaffLoan nextMonth = loan(180, "E1080", StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.DEBT_STANDS,
                NEXT, "GETMORE-1310");
        Voucher spentThisCycle = voucher(30, nextMonth, GetMoreMode.LIVE, utc("2026-11-05T08:00:00Z"),
                utc("2026-11-20T06:00:00Z"));
        spend(80, spentThisCycle, "70.00", utc("2026-11-06T09:00:00Z"));
        StaffLoan tooOld = loan(150, "E1050", StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.DEBT_STANDS,
                PREVIOUS, "GETMORE-1100");
        voucher(5, tooOld, GetMoreMode.LIVE, utc("2026-08-10T08:00:00Z"), null);
        StaffLoan afterCutoff = loan(190, "E1090", StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.DEBT_STANDS,
                NEXT, "GETMORE-1320");
        voucher(31, afterCutoff, GetMoreMode.LIVE, utc("2026-11-20T05:00:00Z"), null);

        StaffLoanCollectionReport.Sync first = service.syncForPayday(PAYDAY, null, 1);
        StaffLoanCollectionReport.Sync second = service.syncForPayday(PAYDAY, first.nextAfter(), 1);
        StaffLoanCollectionReport.Sync third = service.syncForPayday(PAYDAY, second.nextAfter(), 1);

        assertThat(synced).containsExactly(9L, 30L);
        assertThat(first.nextAfter()).isEqualTo(9L);
        assertThat(second.nextAfter()).isEqualTo(30L);
        assertThat(third.nextAfter()).isNull();
        assertThat(first.unchanged() + second.unchanged()).isEqualTo(2);
        StaffLoanCollectionReport report = service.report(PAYDAY);
        assertThat(report.lines()).extracting(Line::loanReference).containsExactly("SGL-2026-000164");
        assertThat(report.merchantSettlement().lines()).extracting(SettlementLine::voucherId)
                .containsExactly(9L, 30L);
        assertThat(report.merchantSettlement().totals().getFirst().net()).isEqualByComparingTo("160.00");
    }

    @Test
    @DisplayName("the payday sync: outcomes counted, cancelled issuances cleaned up on the first page, OFF refused")
    void paydaySyncPages() {
        rutendo();
        StaffLoanVoucherIssuance cancelled = StaffLoanVoucherIssuance.builder().id(77L).build();
        cancelling.add(cancelled);
        when(sync.sync(9L, GetMoreSyncTrigger.PAYDAY)).thenThrow(new IllegalStateException("vault off"));

        StaffLoanCollectionReport.Sync first = service.syncForPayday(PAYDAY, 0L, 50);
        StaffLoanCollectionReport.Sync again = service.syncForPayday(PAYDAY, 3L, 50);

        assertThat(first.unavailable()).as("a voucher that cannot be synced is counted, the rest go on").isEqualTo(1);
        assertThat(first.cleanedUp()).isEqualTo(1);
        assertThat(first.nextAfter()).isNull();
        assertThat(again.cleanedUp()).as("only the first page cleans up").isZero();
        verify(issuer, times(1)).cleanup(77L);
        assertThatThrownBy(() -> service.syncForPayday(PAYDAY, 0L, 201)).isInstanceOf(ValidationException.class)
                .hasMessage("limit must be between 1 and 200");
        assertThatThrownBy(() -> service.syncForPayday(PAYDAY, -1L, 50)).isInstanceOf(ValidationException.class);

        getMore.setMode(GetMoreMode.OFF);
        assertThatThrownBy(() -> service.syncForPayday(PAYDAY, 0L, 50))
                .isInstanceOf(GetMoreUnavailableException.class)
                .hasMessage("GetMore unavailable (OFF): GetMore is OFF on this host (GETMORE_MODE): nothing can be"
                        + " read for payday 20 Nov 2026");
    }

    @Test
    @DisplayName("an accepted loan whose voucher was never issued owes nothing; the CSV is safe for a spreadsheet")
    void awaitingAndCsv() {
        rutendo();
        StaffLoan awaiting = loan(175, "E1075", StaffLoanStatus.AWAITING_DISBURSEMENT,
                UnredeemedVoucherTreatment.DEBT_STANDS, PAYDAY, null);
        ReflectionTestUtils.setField(awaiting, "fullName", "=HYPERLINK(\"x\")");

        StaffLoanCollectionReport report = service.report(PAYDAY);
        assertThat(line(report, "SGL-2026-000175")).satisfies(line -> {
            assertThat(line.amountToCollect()).isEqualByComparingTo("0.00");
            assertThat(line.finalAmount()).isTrue();
            assertThat(line.flags()).containsExactly(StaffLoanOwed.Flag.VOUCHER_NOT_ISSUED);
            assertThat(line.voucherId()).isNull();
        });

        StaffLoanCollectionReport.Csv csv = service.csv(PAYDAY);
        assertThat(csv.fileName()).isEqualTo("staff-loan-collections-2026-11-20.csv");
        String[] rows = csv.content().split("\r\n");
        assertThat(rows[0]).isEqualTo("loanReference,employeeNumber,fullName,status,treatment,voucherId,"
                + "voucherStateAtCutoff,stoppedReason,currency,faceValue,spentNet,refunded,held,openBalance,"
                + "amountToCollect,final,flags,lastSyncedAt,simulated");
        assertThat(rows[1]).isEqualTo("SGL-2026-000164,E1064,Borrower E1064,DISBURSED,DEBT_STANDS,9,EXPIRED,,USD,"
                + "150.00,90.00,10.00,0.00,0.00,150.00,true,,2026-11-20T08:00:00+02:00,false");
        assertThat(rows[2]).startsWith("SGL-2026-000175,E1075,\"'=HYPERLINK(\"\"x\"\")\",AWAITING_DISBURSEMENT,")
                .endsWith(",0.00,true,VOUCHER_NOT_ISSUED,,false");
    }

    @Test
    @DisplayName("stopped by the bank before the cut-off under DEBT_STANDS: what was spent, provisional")
    void bankStopped() {
        StaffLoan loan = loan(176, "E1076", StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.DEBT_STANDS,
                PAYDAY, "GETMORE-1330");
        Voucher voucher = voucher(32, loan, GetMoreMode.LIVE, utc("2026-10-22T08:00:00Z"),
                utc("2026-11-20T06:00:00Z"));
        spend(90, voucher, "20.00", utc("2026-10-23T08:00:00Z"));
        ReflectionTestUtils.setField(voucher, "expiresAt", utc("2026-10-24T08:00:00Z"));
        ReflectionTestUtils.setField(voucher, "stoppedReason", VoucherStopReason.BANK_STOPPED);

        Line line = line(service.report(PAYDAY), "SGL-2026-000176");

        assertThat(line.stoppedReason()).isEqualTo(VoucherStopReason.BANK_STOPPED);
        assertThat(line.amountToCollect()).isEqualByComparingTo("20.00");
        assertThat(line.finalAmount()).isFalse();
        assertThat(line.couldRiseTo()).isEqualByComparingTo("150.00");
        assertThat(line.flags()).containsExactly(StaffLoanOwed.Flag.VOUCHER_STOPPED);
    }

    @Test
    @DisplayName("the cycle's previous payday and the late window follow the repayment day")
    void previousCutoff() {
        Voucher voucher = rutendo();
        spend(46, voucher, "0.50", PREVIOUS_CUTOFF.minusSeconds(1));
        cycles.put(new StaffLoanCollectionCycle.Key(PREVIOUS, GetMoreMode.LIVE), StaffLoanCollectionCycle.builder()
                .payday(PREVIOUS).storeMode(GetMoreMode.LIVE).cutoff(PREVIOUS_CUTOFF)
                .finalisedAt(utc("2026-10-20T06:30:00Z")).finalisedBy("finance1").build());

        StaffLoanCollectionReport report = service.report(PAYDAY);

        assertThat(report.merchantSettlement().previousCycle().finalisedAt()).isEqualTo(utc("2026-10-20T06:30:00Z"));
        assertThat(report.merchantSettlement().lines()).extracting(SettlementLine::late, SettlementLine::spends)
                .containsExactly(tuple(false, new BigDecimal("54.50")), tuple(true, new BigDecimal("46.00")));
    }

    // ---- what cannot take a payday down ---------------------------------------------------------------------------

    @Test
    @DisplayName("a voucher whose rows come to more than the repayment is a flagged provisional line, not a 500")
    void rowsOutOfRangeDoNotTakeTheStatementDown() {
        rutendo();
        StaffLoan loan = loan(179, "E1079", StaffLoanStatus.DISBURSED,
                UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT, PAYDAY, "GETMORE-1340");
        Voucher voucher = voucher(34, loan, GetMoreMode.LIVE, utc("2026-10-25T08:00:00Z"),
                utc("2026-11-20T06:00:00Z"));
        spend(95, voucher, 15L, "150.00", utc("2026-10-26T09:00:00Z"));
        spend(96, voucher, 16L, "40.00", utc("2026-11-02T09:00:00Z"));

        StaffLoanCollectionReport report = service.report(PAYDAY);

        assertThat(line(report, "SGL-2026-000179")).satisfies(line -> {
            assertThat(line.spentNet()).isEqualByComparingTo("190.00");
            assertThat(line.amountToCollect()).isEqualByComparingTo("150.00");
            assertThat(line.finalAmount()).isFalse();
            assertThat(line.couldRiseTo()).isEqualByComparingTo("150.00");
            assertThat(line.flags()).containsExactly(StaffLoanOwed.Flag.ROWS_INCONSISTENT);
        });
        assertThat(line(report, "SGL-2026-000164").amountToCollect()).isEqualByComparingTo("150.00");
        assertThat(report.totals().getFirst().provisionalLoans()).isEqualTo(1);
        assertThat(service.csv(PAYDAY).content()).contains(",150.00,false,ROWS_INCONSISTENT,");
        assertThatThrownBy(() -> service.finalise(PAYDAY, null)).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("1 line is not final and 0 vouchers are stale");

        service.finalise(PAYDAY, new FinaliseCollectionRequest(true, "E1079's GetMore rows are being checked"));

        assertThat(lines).filteredOn(kept -> kept.getStaffLoanId() == 179L).singleElement().satisfies(kept -> {
            assertThat(kept.getAmountToCollect()).isEqualByComparingTo("150.00");
            assertThat(kept.getFlags()).isEqualTo("ROWS_INCONSISTENT");
        });
        assertThat(line(service.report(PAYDAY), "SGL-2026-000179").finalAmount()).as("finalised, read again")
                .isFalse();
    }

    @Test
    @DisplayName("a sale recorded between the settlement's read and the lines' read is in neither: late next payday")
    void aSaleRecordedWhileFinalisingIsInNeither() {
        StaffLoan loan = loan(177, "E1077", StaffLoanStatus.DISBURSED,
                UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT, PAYDAY, "GETMORE-1335");
        Voucher voucher = voucher(33, loan, GetMoreMode.LIVE, utc("2026-10-25T08:00:00Z"),
                utc("2026-11-20T06:00:00Z"));
        ReflectionTestUtils.setField(voucher, "expiresAt", utc("2026-11-10T08:00:00Z"));
        VoucherRedemption known = spend(91, voucher, 13L, "40.00", utc("2026-10-26T09:00:00Z"));
        // A sale ordered on 5 November and invoiced only now: dated by its order, before the cut-off.
        AtomicReference<VoucherRedemption> invoicedNow = new AtomicReference<>();
        afterNextRowRead.set(() -> invoicedNow.set(spend(92, voucher, 14L, "25.00", utc("2026-11-05T10:00:00Z"))));

        StaffLoanCollectionReport finalised = service.finalise(PAYDAY, null);

        assertThat(invoicedNow.get()).as("recorded while it was finalised").isNotNull();
        assertThat(lines).singleElement().satisfies(kept -> {
            assertThat(kept.getAmountToCollect()).as("what the stamped rows add up to").isEqualByComparingTo("40.00");
            assertThat(kept.isFinalAmount()).isTrue();
        });
        assertThat(known.getSettlementCycle()).isEqualTo(PAYDAY);
        assertThat(invoicedNow.get().getSettlementCycle()).as("not paid to GetMore on a line that never counted it")
                .isNull();
        assertThat(finalised.merchantSettlement().totals().getFirst().net()).isEqualByComparingTo("40.00");
        assertThat(finalised.lateAdjustments()).singleElement().satisfies(late -> {
            assertThat(late.loanReference()).isEqualTo("SGL-2026-000177");
            assertThat(late.spends()).isEqualByComparingTo("25.00");
        });
        assertThat(service.report(NEXT).merchantSettlement().lines()).singleElement().satisfies(late -> {
            assertThat(late.voucherId()).isEqualTo(33L);
            assertThat(late.late()).isTrue();
            assertThat(late.spends()).isEqualByComparingTo("25.00");
        });
    }

    @Test
    @DisplayName("the cycle's key is the last word on a double finalisation: nothing kept, stamped or audited")
    void finaliseLosesTheRaceOnTheCycleKey() {
        rutendo();
        doThrow(new DataIntegrityViolationException("staff_loan_collection_cycles_pkey")).when(cycleRepository)
                .saveAndFlush(any());

        assertThatThrownBy(() -> service.finalise(PAYDAY, null)).isInstanceOf(ConflictException.class)
                .hasMessage("Payday 20 Nov 2026 is already finalised");

        verify(lineRepository, never()).saveAll(any());
        verify(redemptionRepository, never()).stampSettlementCycle(any(), any());
        verify(auditService, never()).record(any());
        assertThat(spends).allSatisfy(spend -> assertThat(spend.getSettlementCycle()).isNull());
    }

    @Test
    @DisplayName("rows found settled already while stamping: refused, nothing audited")
    void finaliseFindsItsRowsSettledAlready() {
        rutendo();
        doAnswer(i -> ((Collection<?>) i.getArgument(0)).size() - 1).when(redemptionRepository)
                .stampSettlementCycle(any(), any());

        assertThatThrownBy(() -> service.finalise(PAYDAY, null)).isInstanceOf(ConflictException.class)
                .hasMessage(StaffLoanCollectionService.settledConcurrently(PAYDAY))
                .hasMessageStartingWith("GetMore sales or refunds of payday 20 Nov 2026 were settled by another"
                        + " finalisation");

        verify(auditService, never()).record(any());
    }

    private static GetMoreSyncResult result(GetMoreSyncResult.Outcome outcome) {
        return new GetMoreSyncResult(outcome, 0, 0, null, 0, List.of(), false, null);
    }

    /** Vouchers 40 to 45 of loans due next month, each issued this cycle and never read. */
    private void sixVouchers() {
        for (long id = 40; id <= 45; id++) {
            StaffLoan loan = loan(200 + id, "E12" + id, StaffLoanStatus.DISBURSED,
                    UnredeemedVoucherTreatment.DEBT_STANDS, NEXT, "GETMORE-14" + id);
            voucher(id, loan, GetMoreMode.LIVE, utc("2026-11-02T08:00:00Z"), null);
        }
    }

    @Test
    @DisplayName("the payday sync stops a page after 3 vouchers in a row GetMore could not be read for")
    void paydaySyncStopsWhenGetMoreFails() {
        sixVouchers();
        Map<Long, GetMoreSyncResult.Outcome> outcomes = Map.of(40L, GetMoreSyncResult.Outcome.UNAVAILABLE,
                41L, GetMoreSyncResult.Outcome.UNCHANGED, 43L, GetMoreSyncResult.Outcome.UNAVAILABLE,
                44L, GetMoreSyncResult.Outcome.UNAVAILABLE, 45L, GetMoreSyncResult.Outcome.UNCHANGED);
        when(sync.sync(anyLong(), eq(GetMoreSyncTrigger.PAYDAY))).thenAnswer(i -> {
            long id = i.getArgument(0);
            synced.add(id);
            if (id == 42L) {
                throw new GetMoreUnavailableException(null, GetMoreUnavailableException.Reason.READ_FAILED,
                        "read timed out");
            }
            return result(outcomes.get(id));
        });

        StaffLoanCollectionReport.Sync first = service.syncForPayday(PAYDAY, 0L, 50);

        assertThat(synced).as("41 broke the first run; 42 to 44 are three in a row").containsExactly(40L, 41L, 42L,
                43L, 44L);
        assertThat(first.stoppedEarly()).isTrue();
        assertThat(first.nextAfter()).isEqualTo(44L);
        assertThat(first.unavailable()).isEqualTo(4);
        assertThat(first.unchanged()).isEqualTo(1);

        StaffLoanCollectionReport.Sync rest = service.syncForPayday(PAYDAY, first.nextAfter(), 50);

        assertThat(synced).endsWith(45L);
        assertThat(rest.stoppedEarly()).isFalse();
        assertThat(rest.nextAfter()).isNull();
    }

    @Test
    @DisplayName("the payday sync starts no voucher once a page has run 60 seconds; the next page goes on from there")
    void paydaySyncStopsWhenGetMoreIsSlow() {
        sixVouchers();
        Function<Long, GetMoreSyncResult> slow = id -> {
            synced.add(id);
            now.set(now.get().plus(StaffLoanCollectionService.SYNC_PAGE_BUDGET).plus(Duration.ofSeconds(1)));
            return result(GetMoreSyncResult.Outcome.UNCHANGED);
        };
        when(sync.sync(anyLong(), eq(GetMoreSyncTrigger.PAYDAY))).thenAnswer(i -> slow.apply(i.getArgument(0)));

        StaffLoanCollectionReport.Sync first = service.syncForPayday(PAYDAY, 0L, 50);
        StaffLoanCollectionReport.Sync second = service.syncForPayday(PAYDAY, first.nextAfter(), 50);

        assertThat(first.stoppedEarly()).isTrue();
        assertThat(first.nextAfter()).isEqualTo(40L);
        assertThat(first.unchanged()).isEqualTo(1);
        assertThat(second.nextAfter()).isEqualTo(41L);
        assertThat(synced).containsExactly(40L, 41L);
    }
}
