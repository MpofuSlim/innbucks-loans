package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreCouponDeletion;
import zw.co.innbucks.loans.core.getmore.GetMoreCoupons;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.VoucherStore;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanVoucherIssuer.Cleanup;
import zw.co.innbucks.loans.core.voucher.GetMoreDispatcher;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult.Outcome;
import zw.co.innbucks.loans.core.voucher.GetMoreSyncTrigger;
import zw.co.innbucks.loans.core.voucher.GetMoreVoucherSync;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherChannel;
import zw.co.innbucks.loans.core.voucher.VoucherCodeSource;
import zw.co.innbucks.loans.core.voucher.VoucherCodeVault;
import zw.co.innbucks.loans.core.voucher.VoucherDeliveryStatus;
import zw.co.innbucks.loans.core.voucher.VoucherProduct;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionRepository;
import zw.co.innbucks.loans.core.voucher.VoucherRefundRepository;
import zw.co.innbucks.loans.core.voucher.VoucherRepository;
import zw.co.innbucks.loans.core.voucher.VoucherService;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.core.voucher.VoucherStopReason;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssue;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueKind;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Credit's cancel (D6) at 10:40 in Harare on 14 October 2026, of Rutendo Chari's SGL-2026-000164 (USD 150.00), awaiting
 * its GetMore voucher or paid out as voucher 9 (coupon 1207). A paid-out loan is cancelled only when GetMore's own
 * records say nothing was ever spent, its coupon withdrawn first and GetMore read again after: each refusal, each
 * failure of GetMore, and each race (a spend, a write-off) leaves the loan standing with nothing half done.
 */
class StaffLoanCancellationTest {

    private static final Instant NOW = Instant.parse("2026-10-14T08:40:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String CODE = "SIM-K7Q2M3XW-7KQ2M9XWD4RT8B1C";
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 10, 14, 8, 20, 7);
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 11, 13, 21, 59, 59, 999_999_000);

    private final MarketTimeZone market = new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC));
    private final VoucherCodeVault vault = GetMoreFakes.vault();
    private final GetMoreProperties properties = new GetMoreProperties();
    private final StaffLoanRepository loans = mock(StaffLoanRepository.class);
    private final StaffLoanVoucherIssuanceRepository issuances = mock(StaffLoanVoucherIssuanceRepository.class);
    private final StaffLoanWriteOffRepository writeOffs = mock(StaffLoanWriteOffRepository.class);
    private final StaffLoanVoucherIssuer issuer = mock(StaffLoanVoucherIssuer.class);
    private final GetMoreVoucherSync sync = mock(GetMoreVoucherSync.class);
    private final GetMoreCoupons coupons = mock(GetMoreCoupons.class);
    private final VoucherService voucherService = mock(VoucherService.class);
    private final VoucherRepository vouchers = mock(VoucherRepository.class);
    private final VoucherRedemptionRepository redemptions = mock(VoucherRedemptionRepository.class);
    private final VoucherRefundRepository refunds = mock(VoucherRefundRepository.class);
    private final VoucherSyncIssueRepository syncIssues = mock(VoucherSyncIssueRepository.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuditService audit = mock(AuditService.class);
    private final List<String> events = new ArrayList<>();
    private final Deque<Supplier<GetMoreSyncResult>> syncs = new ArrayDeque<>();
    private final AtomicReference<Voucher> voucher = new AtomicReference<>();
    private final StaffLoanPolicy policy = new StaffLoanPolicy(new StaffLoanProperties(), new VoucherProperties());
    private StaffLoan loan;
    private StaffLoanCancellation cancellation;

    @BeforeEach
    void setUp() {
        properties.setMode(GetMoreMode.SIMULATED);
        when(authService.getLoggedInUsername()).thenReturn("credit1");
        loan = paidOut();
        when(loans.lockById(164L)).thenAnswer(i -> Optional.of(loan));
        when(loans.findById(164L)).thenAnswer(i -> Optional.of(loan));
        voucher.set(voucher().build());
        when(vouchers.findByStaffLoanId(164L)).thenAnswer(i -> Optional.ofNullable(voucher.get()));
        when(vouchers.findById(9L)).thenAnswer(i -> Optional.ofNullable(voucher.get()));
        when(vouchers.lockById(9L)).thenAnswer(i -> Optional.ofNullable(voucher.get()));
        when(sync.withVoucherLock(anyLong(), any())).thenAnswer(i -> {
            events.add("lock");
            return i.<Supplier<?>>getArgument(1).get();
        });
        when(sync.sync(9L, GetMoreSyncTrigger.CANCELLATION)).thenAnswer(i -> {
            events.add("sync");
            return syncs.isEmpty() ? synced(Outcome.UNCHANGED, 0) : syncs.pop().get();
        });
        when(coupons.withdraw(any())).thenAnswer(i -> {
            events.add("withdraw");
            return new GetMoreCouponDeletion(List.of(), List.of());
        });
        doAnswer(i -> {
            voucher.set(voucher().status(VoucherStatus.EXPIRED).expiresAt(NOW_UTC)
                    .stoppedReason(VoucherStopReason.BANK_STOPPED).couponWithdrawnAt(NOW_UTC).build());
            events.add("stopped");
            return true;
        }).when(voucherService).stopGetMoreNow(eq(9L), eq(VoucherStopReason.BANK_STOPPED), any());
        doAnswer(i -> {
            events.add("voucher cancelled");
            return null;
        }).when(voucherService).cancelGetMoreForLoan(eq(9L), any(), any(), any());
        when(issuer.cleanup(anyLong())).thenReturn(Cleanup.WITHDRAWN);
        StaffLoanService loanService = new StaffLoanService(loans, mock(StaffLoanAgreementRepository.class), policy,
                authService, audit, market, mock(StaffLoanEmploymentFlagNotifier.class), issuances,
                mock(GetMoreDispatcher.class), properties);
        cancellation = new StaffLoanCancellation(loans, issuances, writeOffs, loanService, issuer, policy, sync,
                coupons, properties, voucherService, vouchers, redemptions, refunds, syncIssues, vault, authService,
                market, new GetMoreFakes.Transactions(events));
    }

    private static Merchant getMore() {
        Merchant merchant = Merchant.builder().merchantCode("getmore-groceries").companyName("GetMore Groceries")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).staffLoanMerchant(true)
                .voucherStore(VoucherStore.GETMORE).build();
        merchant.setId(3L);
        return merchant;
    }

    private static StaffLoan.StaffLoanBuilder loanBuilder(StaffLoanStatus status) {
        return StaffLoan.builder().id(164L).reference("SGL-2026-000164").staffMemberId(9L).offerId(64L)
                .employeeNumber("E1064").fullName("Rutendo Chari").msisdn("263771640064").grade("C4")
                .merchant(getMore()).amount(new BigDecimal("150.00")).currency("USD").interestRate(BigDecimal.ZERO)
                .totalRepayable(new BigDecimal("150.00")).dueDate(LocalDate.of(2026, 11, 20))
                .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS).status(status)
                .acceptedAt(LocalDateTime.of(2026, 10, 14, 8, 20, 5)).version(0L);
    }

    private static StaffLoan paidOut() {
        return loanBuilder(StaffLoanStatus.DISBURSED).disbursedAt(ISSUED_AT).disbursementReference("GETMORE-SIM-1207")
                .build();
    }

    private Voucher.VoucherBuilder voucher() {
        return Voucher.builder().id(9L).product(VoucherProduct.STAFF_GROCERY_LOAN).merchant(getMore())
                .disbursementReference("GETMORE-SIM-1207").loanAccount("SGL-2026-000164").staffMemberId(9L)
                .customerReference("E1064").customerName("Rutendo Chari").customerMsisdn("+263771640064")
                .codeHmac(vault.fingerprint(CODE)).codeCiphertext(vault.encrypt(CODE)).codeLast4("8B1C")
                .codeLength(CODE.length()).faceValue(new BigDecimal("150.00")).redeemedAmount(new BigDecimal("0.00"))
                .currency("USD").issuedAt(ISSUED_AT).expiresAt(EXPIRES_AT).status(VoucherStatus.ISSUED)
                .deliveryStatus(VoucherDeliveryStatus.SENT).deliveredChannel(VoucherChannel.WHATSAPP)
                .deliveryUpdatedAt(ISSUED_AT).version(0L).codeSource(VoucherCodeSource.GETMORE)
                .storeMode(GetMoreMode.SIMULATED).staffLoanId(164L).externalCouponId(1207L).externalRuleId(43L)
                .codePrefix("SIM-K7Q2M3XW-");
    }

    private static GetMoreSyncResult synced(Outcome outcome, int liveOrders) {
        return new GetMoreSyncResult(outcome, 0, 0, BigDecimal.ZERO, liveOrders, List.of(), false, null);
    }

    private StaffLoanVoucherIssuance issuance(StaffLoanVoucherIssuanceStatus status, LocalDateTime claimedAt) {
        StaffLoanVoucherIssuance issuance = StaffLoanVoucherIssuance.builder().id(101L).staffLoanId(164L)
                .merchantId(3L).storeMode(GetMoreMode.SIMULATED).couponPrefix("SIM-K7Q2M3XW-").ruleId(43L)
                .faceValue(new BigDecimal("150.00")).currency("USD").status(StaffLoanVoucherIssuanceStatus.PENDING)
                .nextAttemptAt(NOW_UTC).createdAt(NOW_UTC).updatedAt(NOW_UTC).build();
        switch (status) {
            case CALLING -> issuance.claim(claimedAt);
            case PARKED -> issuance.park("UNAVAILABLE_NOT_REACHED", null, NOW_UTC);
            case ABANDONED -> {
                issuance.cancelling(NOW_UTC);
                issuance.abandoned(NOW_UTC);
            }
            default -> {
            }
        }
        when(issuances.lockByStaffLoanId(164L)).thenReturn(Optional.of(issuance));
        return issuance;
    }

    private StaffLoanCancellation.Result cancel() {
        return cancellation.cancel(164L, "  Accepted in error  ");
    }

    // ---- awaiting its voucher -------------------------------------------------------------------------------------

    @Test
    @DisplayName("(a) awaiting, with no GetMore issuance or an abandoned one: cancelled as before, GetMore not asked")
    void awaitingWithoutAnIssuance() {
        loan = loanBuilder(StaffLoanStatus.AWAITING_DISBURSEMENT).build();

        StaffLoanCancellation.Result result = cancel();

        assertThat(result.message()).isEqualTo("Cancelled");
        assertThat(result.loan().status()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(result.loan().cancellationReason()).isEqualTo("Accepted in error");
        assertThat(result.loan().cancelledBy()).isEqualTo("credit1");
        verify(issuer, never()).cleanup(anyLong());

        loan = loanBuilder(StaffLoanStatus.AWAITING_DISBURSEMENT).build();
        StaffLoanVoucherIssuance abandoned = issuance(StaffLoanVoucherIssuanceStatus.ABANDONED, null);
        assertThat(cancel().message()).isEqualTo("Cancelled");
        assertThat(abandoned.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.ABANDONED);
        verify(sync, never()).sync(anyLong(), any());
        verify(coupons, never()).withdraw(any());
    }

    @Test
    @DisplayName("(b) awaiting, with an attempt calling GetMore right now: 409 in progress, nothing cancelled")
    void awaitingWhileCalling() {
        loan = loanBuilder(StaffLoanStatus.AWAITING_DISBURSEMENT).build();
        issuance(StaffLoanVoucherIssuanceStatus.CALLING, NOW_UTC.minusSeconds(30));

        assertThatThrownBy(this::cancel).isInstanceOf(IssuanceInProgressException.class)
                .hasMessage("A voucher for SGL-2026-000164 is being issued right now; try again in a minute.");

        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(events).contains("rollback");
        verify(issuer, never()).cleanup(anyLong());
    }

    @Test
    @DisplayName("(c) awaiting, queued, parked or stale: cancelled, CANCELLING, its coupons withdrawn there and then")
    void awaitingWithAnIssuance() {
        loan = loanBuilder(StaffLoanStatus.AWAITING_DISBURSEMENT).build();
        StaffLoanVoucherIssuance stale = issuance(StaffLoanVoucherIssuanceStatus.CALLING, NOW_UTC.minusMinutes(6));

        StaffLoanCancellation.Result result = cancel();

        assertThat(result.loan().status()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(result.message()).isEqualTo("Cancelled. The voucher's coupon is withdrawn at GetMore.");
        assertThat(stale.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.CANCELLING);
        assertThat(stale.getClaimedAt()).isNull();
        verify(issuer).cleanup(101L);

        loan = loanBuilder(StaffLoanStatus.AWAITING_DISBURSEMENT).build();
        issuance(StaffLoanVoucherIssuanceStatus.PARKED, null);
        when(issuer.cleanup(101L)).thenReturn(Cleanup.FAILED);
        assertThat(cancel().message()).isEqualTo("Cancelled. Withdrawing the voucher's coupon at GetMore is still"
                + " pending; it is retried on the next voucher-issuance call or payday sync.");
    }

    // ---- paid out as its GetMore voucher ---------------------------------------------------------------------------

    @Test
    @DisplayName("(d) paid out, never spent: read GetMore, withdraw the coupon, read again, then cancel both")
    void paidOutUntouched() {
        StaffLoanCancellation.Result result = cancel();

        assertThat(result.message()).isEqualTo("Cancelled. The voucher's coupon is withdrawn at GetMore.");
        assertThat(result.loan().status()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(loan.getDisbursedAt()).as("it was paid out, and keeps when").isEqualTo(ISSUED_AT);
        assertThat(loan.getDisbursementReference()).isEqualTo("GETMORE-SIM-1207");
        assertThat(events).containsSubsequence("lock", "sync", "withdraw", "sync", "voucher cancelled");
        verify(coupons).withdraw(List.of(CODE));
        InOrder order = inOrder(coupons, voucherService);
        order.verify(coupons).withdraw(List.of(CODE));
        order.verify(voucherService).markCouponWithdrawn(eq(9L), any());
        order.verify(voucherService).cancelGetMoreForLoan(9L, "credit1", "Accepted in error", NOW_UTC);
        verify(voucherService, never()).stopGetMoreNow(anyLong(), any(), any());
    }

    @Test
    @DisplayName("(e) anything else keeps the old refusal: closed, or paid out other than as a GetMore voucher")
    void otherwiseRefused() {
        loan = loanBuilder(StaffLoanStatus.REPAID).disbursedAt(ISSUED_AT).disbursementReference("GETMORE-SIM-1207")
                .build();
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000164 is REPAID; only a loan awaiting disbursement can be"
                        + " cancelled");

        loan = paidOut();
        voucher.set(null);
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000164 is DISBURSED; only a loan awaiting disbursement can be"
                        + " cancelled");

        voucher.set(voucher().build());
        properties.setMode(GetMoreMode.LIVE);
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessageContaining("coupon of the SIMULATED GetMore store and GetMore is LIVE");

        when(loans.lockById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> cancellation.cancel(9L, "why")).isInstanceOf(NotFoundException.class);
        verify(sync, never()).sync(anyLong(), any());
    }

    @Test
    @DisplayName("a sale held at the till (ordered, not invoiced) refuses, before anything is withdrawn")
    void heldSaleRefuses() {
        voucher.set(voucher().heldAmount(new BigDecimal("20.00")).build());

        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessage("The voucher of SGL-2026-000164 has been used (USD 0.00 spent, USD 20.00 held at the"
                        + " till); a loan whose voucher was used cannot be cancelled.");

        verify(coupons, never()).withdraw(any());
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
    }

    @Test
    @DisplayName("spent, refunded back to nothing, a recorded row, or an order standing at GetMore: all refuse")
    void anyUseRefuses() {
        voucher.set(voucher().redeemedAmount(new BigDecimal("45.50")).status(VoucherStatus.PARTIALLY_REDEEMED)
                .lastRedeemedAt(NOW_UTC).lastRedeemedOutlet("GetMore Avondale").build());
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessageContaining("(USD 45.50 spent, USD 0.00 held at the till)");

        voucher.set(voucher().refundedAmount(new BigDecimal("10.00")).build());
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class).hasMessageContaining("has been used");

        voucher.set(voucher().build());
        when(redemptions.existsByVoucherId(9L)).thenReturn(true);
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class).hasMessageContaining("has been used");
        when(redemptions.existsByVoucherId(9L)).thenReturn(false);

        syncs.add(() -> synced(Outcome.UNCHANGED, 1));
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class).hasMessageContaining("has been used");

        verify(coupons, never()).withdraw(any());
    }

    @Test
    @DisplayName("an order on a voucher whose sync is blocked refuses: the open issue is named")
    void blockedVoucherRefuses() {
        voucher.set(voucher().syncBlockedBy(VoucherSyncIssueKind.OVERSPENT).build());
        syncs.add(() -> synced(Outcome.BLOCKED, 0));

        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessage("Voucher 9 has an open GetMore sync issue (OVERSPENT); resolve it before cancelling.");

        voucher.set(voucher().build());
        when(syncIssues.findByVoucherIdAndClearedAtIsNullOrderByIdAsc(9L)).thenReturn(List.of(
                VoucherSyncIssue.builder().voucherId(9L).kind(VoucherSyncIssueKind.SPEND_ON_OTHER_COUPON)
                        .blocking(true).externalRef("order:14").detail("a sale on another coupon")
                        .firstSeenAt(NOW_UTC).lastSeenAt(NOW_UTC).build()));
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessageContaining("(SPEND_ON_OTHER_COUPON)");
        verify(coupons, never()).withdraw(any());
    }

    @Test
    @DisplayName("a voucher that lapsed, or was stopped other than by a cancellation, keeps its loan")
    void lapsedVoucherRefuses() {
        voucher.set(voucher().expiresAt(NOW_UTC.minusSeconds(1)).build());
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessage("Voucher 9 of SGL-2026-000164 is EXPIRED; only a loan whose voucher can still be spent can"
                        + " be cancelled.");

        voucher.set(voucher().status(VoucherStatus.EXPIRED).expiresAt(NOW_UTC)
                .stoppedReason(VoucherStopReason.STORE_WITHDRAWN).build());
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessageContaining("is EXPIRED (STORE_WITHDRAWN)");
        verify(coupons, never()).withdraw(any());
    }

    @Test
    @DisplayName("GetMore unreadable before: 503, nothing withdrawn; another sync running: 503 BUSY")
    void firstSyncUnavailable() {
        syncs.add(() -> synced(Outcome.UNAVAILABLE, 0));

        assertThatThrownBy(this::cancel).isInstanceOf(GetMoreUnavailableException.class)
                .hasMessageContaining("Cannot read voucher 9 at GetMore right now; try again.")
                .satisfies(e -> assertThat(((GetMoreUnavailableException) e).reason())
                        .isEqualTo(GetMoreUnavailableException.Reason.READ_FAILED));
        verify(coupons, never()).withdraw(any());

        doThrow(new GetMoreUnavailableException(null, GetMoreUnavailableException.Reason.BUSY,
                "another sync of voucher 9 is running")).when(sync).withVoucherLock(anyLong(), any());
        assertThatThrownBy(this::cancel).isInstanceOf(GetMoreUnavailableException.class)
                .satisfies(e -> assertThat(((GetMoreUnavailableException) e).reason())
                        .isEqualTo(GetMoreUnavailableException.Reason.BUSY));
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
    }

    @Test
    @DisplayName("GetMore will not withdraw, or its answer is lost: nothing changes in loans")
    void withdrawalFails() {
        when(coupons.withdraw(any())).thenReturn(new GetMoreCouponDeletion(List.of(CODE), List.of()));
        assertThatThrownBy(this::cancel).isInstanceOf(GetMoreRejectedException.class)
                .hasMessageContaining("GetMore did not withdraw the coupon of voucher 9 (****8B1C)")
                .hasMessageNotContaining(CODE);

        when(coupons.withdraw(any())).thenThrow(new GetMoreOutcomeUnknownException(Operation.DELETE_COUPONS,
                "read timed out"));
        assertThatThrownBy(this::cancel).isInstanceOf(GetMoreOutcomeUnknownException.class);

        verify(voucherService, never()).markCouponWithdrawn(anyLong(), any());
        verify(voucherService, never()).stopGetMoreNow(anyLong(), any(), any());
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
    }

    @Test
    @DisplayName("GetMore unreadable after the withdrawal: the voucher is stopped, the loan stands; a repeat finishes")
    void secondSyncUnavailableThenRepeat() {
        syncs.add(() -> synced(Outcome.UNCHANGED, 0));
        syncs.add(() -> synced(Outcome.UNAVAILABLE, 0));

        assertThatThrownBy(this::cancel).isInstanceOf(GetMoreUnavailableException.class)
                .hasMessageContaining("The coupon is withdrawn at GetMore; repeat the request to finish cancelling: it"
                        + " is safe.");
        verify(voucherService).stopGetMoreNow(eq(9L), eq(VoucherStopReason.BANK_STOPPED), any());
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        verify(voucherService, never()).cancelGetMoreForLoan(anyLong(), any(), any(), any());

        when(coupons.withdraw(any())).thenReturn(new GetMoreCouponDeletion(List.of(), List.of(CODE)));
        StaffLoanCancellation.Result repeated = cancel();

        assertThat(repeated.loan().status()).isEqualTo(StaffLoanStatus.CANCELLED);
        verify(voucherService).cancelGetMoreForLoan(9L, "credit1", "Accepted in error", NOW_UTC);
    }

    @Test
    @DisplayName("a write-off approved or pending meanwhile: the voucher is stopped and the loan stands, a 409")
    void writeOffMeanwhile() {
        syncs.add(() -> synced(Outcome.UNCHANGED, 0));
        syncs.add(() -> {
            when(writeOffs.findByStaffLoanIdAndStatus(164L, StaffLoanWriteOffStatus.PENDING))
                    .thenReturn(Optional.of(StaffLoanWriteOff.builder().id(5L).staffLoanId(164L).build()));
            return synced(Outcome.UNCHANGED, 0);
        });
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000164 has a write-off pending; its coupon is withdrawn and the loan"
                        + " stands.");
        verify(voucherService).stopGetMoreNow(eq(9L), eq(VoucherStopReason.BANK_STOPPED), any());
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);

        when(writeOffs.findByStaffLoanIdAndStatus(164L, StaffLoanWriteOffStatus.PENDING)).thenReturn(Optional.empty());
        loan = paidOut();
        voucher.set(voucher().build());
        syncs.add(() -> synced(Outcome.UNCHANGED, 0));
        syncs.add(() -> {
            loan.writeOff(NOW_UTC);
            return synced(Outcome.UNCHANGED, 0);
        });
        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000164 is WRITTEN_OFF; its coupon is withdrawn and the loan stands.");
        verify(voucherService, never()).cancelGetMoreForLoan(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("a spend that reached the till before the withdrawal: the voucher is stopped and the loan stands")
    void spendRacedIn() {
        syncs.add(() -> synced(Outcome.UNCHANGED, 0));
        syncs.add(() -> {
            voucher.set(voucher().redeemedAmount(new BigDecimal("20.00")).status(VoucherStatus.PARTIALLY_REDEEMED)
                    .lastRedeemedAt(NOW_UTC).lastRedeemedOutlet("GetMore Avondale").build());
            return synced(Outcome.SYNCED, 1);
        });

        assertThatThrownBy(this::cancel).isInstanceOf(ConflictException.class)
                .hasMessage("A spend of USD 20.00 reached the voucher while cancelling; it has been stopped at GetMore"
                        + " and the loan stands.");

        verify(voucherService).stopGetMoreNow(eq(9L), eq(VoucherStopReason.BANK_STOPPED), any());
        verify(voucherService, never()).cancelGetMoreForLoan(anyLong(), any(), any(), any());
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
    }
}
