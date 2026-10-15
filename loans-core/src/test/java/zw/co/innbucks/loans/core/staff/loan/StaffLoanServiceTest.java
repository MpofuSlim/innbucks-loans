package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.getmore.GetMoreProperties;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatusChanged;
import zw.co.innbucks.loans.core.voucher.GetMoreDispatcher;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Staff loans as the portal handles them: Credit cancels one before payout, a borrower who stops being ACTIVE has
 * theirs cancelled at once, or flagged once it is paid out, and the agreement is shown with its seal checked. The
 * clock stands at 1 October 2026, 11:02:17 in Harare.
 */
class StaffLoanServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 9, 2, 17);

    private final StaffLoanRepository loans = mock(StaffLoanRepository.class);
    private final StaffLoanAgreementRepository agreements = mock(StaffLoanAgreementRepository.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuditService audit = mock(AuditService.class);
    private final StaffLoanEmploymentFlagNotifier notifier = mock(StaffLoanEmploymentFlagNotifier.class);
    private final StaffLoanVoucherIssuanceRepository issuances = mock(StaffLoanVoucherIssuanceRepository.class);
    private final GetMoreDispatcher dispatcher = mock(GetMoreDispatcher.class);
    private final StaffLoanService service = new StaffLoanService(loans, agreements,
            new StaffLoanPolicy(new StaffLoanProperties(), new VoucherProperties()), authService, audit,
            new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-10-01T09:02:17Z"), ZoneOffset.UTC)), notifier,
            issuances, dispatcher, new GetMoreProperties());
    private final StaffLoan loan = StaffLoan.builder().id(143L).reference("SGL-2026-000143").staffMemberId(2L)
            .offerId(31L).employeeNumber("E1012").fullName("Chipo Banda").msisdn("263773456789").grade("C4")
            .amount(new BigDecimal("300.00")).currency("USD").interestRate(BigDecimal.ZERO)
            .totalRepayable(new BigDecimal("300.00")).dueDate(LocalDate.of(2026, 11, 20))
            .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS)
            .status(StaffLoanStatus.AWAITING_DISBURSEMENT).acceptedAt(LocalDateTime.of(2026, 10, 1, 7, 10, 41))
            .merchant(getMore()).build();

    /** Nyasha Dube's loan, paid out on 8 October. */
    private final StaffLoan paidOut = paidOutLoan(StaffLoanStatus.DISBURSED);

    private static StaffLoan paidOutLoan(StaffLoanStatus status) {
        return StaffLoan.builder().id(151L).reference("SGL-2026-000151").staffMemberId(1L)
                .offerId(1L).employeeNumber("E1001").fullName("Nyasha Dube").msisdn("263782606983").grade("C4")
                .amount(new BigDecimal("250.00")).currency("USD").interestRate(BigDecimal.ZERO)
                .totalRepayable(new BigDecimal("250.00")).dueDate(LocalDate.of(2026, 11, 20))
                .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS)
                .status(status).acceptedAt(LocalDateTime.of(2026, 10, 8, 5, 58, 30))
                .disbursedAt(LocalDateTime.of(2026, 10, 8, 6, 5, 38)).disbursementReference("BRNET-20261008-0002")
                .merchant(getMore()).build();
    }

    private static Merchant getMore() {
        Merchant merchant = Merchant.builder().merchantCode("getmore-groceries").companyName("GetMore Groceries")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).staffLoanMerchant(true).build();
        merchant.setId(3L);
        return merchant;
    }

    {
        when(loans.lockById(143L)).thenReturn(Optional.of(loan));
        when(loans.findById(143L)).thenReturn(Optional.of(loan));
        when(loans.lockById(151L)).thenReturn(Optional.of(paidOut));
        when(loans.findById(151L)).thenReturn(Optional.of(paidOut));
        when(authService.getLoggedInUsername()).thenReturn("cmanager");
    }

    @Test
    @DisplayName("Credit cancels a loan awaiting payout, with who and why; the number stays masked")
    void cancel() {
        StaffLoanResponse cancelled = service.cancel(143L, "  Accepted in error  ");

        assertThat(cancelled.status()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isEqualTo(NOW);
        assertThat(cancelled.cancelledBy()).isEqualTo("cmanager");
        assertThat(cancelled.cancellationReason()).isEqualTo("Accepted in error");
        assertThat(cancelled.msisdn()).isEqualTo("****6789");
        assertThat(loan.outstanding()).isEqualByComparingTo("0.00");
        verify(loans).save(loan);
        assertThat(audited().getEventType()).isEqualTo(StaffLoanService.CANCELLED);
        assertThat(audited().getChannelUsed()).isEqualTo("admin-portal");
    }

    @AfterEach
    void endTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** The loan's GetMore voucher issuance, as {@code status}; CALLING claimed {@code claimedAgo} before now. */
    private StaffLoanVoucherIssuance issuance(StaffLoanVoucherIssuanceStatus status, Duration claimedAgo) {
        StaffLoanVoucherIssuance issuance = StaffLoanVoucherIssuance.builder().id(101L).staffLoanId(143L)
                .merchantId(3L).storeMode(GetMoreMode.SIMULATED).couponPrefix("SIM-K7Q2M3XW-").ruleId(43L)
                .faceValue(new BigDecimal("300.00")).currency("USD").status(StaffLoanVoucherIssuanceStatus.PENDING)
                .nextAttemptAt(NOW).createdAt(NOW).updatedAt(NOW).build();
        if (status == StaffLoanVoucherIssuanceStatus.CALLING) {
            issuance.claim(NOW.minus(claimedAgo));
        } else if (status == StaffLoanVoucherIssuanceStatus.PARKED) {
            issuance.park("UNAVAILABLE_NOT_REACHED", null, NOW);
        }
        when(issuances.lockByStaffLoanId(143L)).thenReturn(Optional.of(issuance));
        return issuance;
    }

    @Test
    @DisplayName("Credit's cancel of a loan whose GetMore voucher is queued: CANCELLING, withdrawn once it commits")
    void cancelWithdrawsTheQueuedVoucher() {
        StaffLoanVoucherIssuance issuance = issuance(StaffLoanVoucherIssuanceStatus.PENDING, null);
        TransactionSynchronizationManager.initSynchronization();

        service.cancel(143L, "Accepted in error");

        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(issuance.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.CANCELLING);
        verify(issuances).save(issuance);
        verify(dispatcher, never()).cleanup(anyLong());
        TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());
        verify(dispatcher).cleanup(101L);
    }

    @Test
    @DisplayName("Credit's cancel while the voucher is being issued right now: refused, nothing cancelled")
    void cancelRefusedWhileCalling() {
        StaffLoanVoucherIssuance issuance = issuance(StaffLoanVoucherIssuanceStatus.CALLING,
                Duration.ofSeconds(20));

        assertThatThrownBy(() -> service.cancel(143L, "Accepted in error"))
                .isInstanceOf(IssuanceInProgressException.class)
                .hasMessage("A voucher for SGL-2026-000143 is being issued right now; try again in a minute.");
        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(issuance.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.CALLING);
        verify(dispatcher, never()).cleanup(anyLong());
    }

    @ParameterizedTest
    @EnumSource(value = StaffLoanVoucherIssuanceStatus.class, names = {"PENDING", "PARKED", "CALLING"})
    @DisplayName("an employment change cancels the loan; a queued, parked or stale issuance goes CANCELLING and is"
            + " withdrawn once the register change commits")
    void employmentChangeSendsTheIssuanceToCancelling(StaffLoanVoucherIssuanceStatus status) {
        StaffLoanVoucherIssuance issuance = issuance(status, Duration.ofMinutes(6));
        when(loans.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(anyLong(), any())).thenReturn(Optional.of(loan));
        TransactionSynchronizationManager.initSynchronization();

        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(2L, "E1012",
                StaffEmploymentStatus.ACTIVE, StaffEmploymentStatus.RESIGNED, 17L, "hc2"));

        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(issuance.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.CANCELLING);
        assertThat(issuance.getClaimedAt()).isNull();
        verify(dispatcher, never()).cleanup(anyLong());
        TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());
        verify(dispatcher).cleanup(101L);
    }

    @Test
    @DisplayName("an employment change leaves a fresh claim to its attempt, which finds the loan cancelled when it"
            + " settles")
    void employmentChangeLeavesAFreshClaimAlone() {
        StaffLoanVoucherIssuance issuance = issuance(StaffLoanVoucherIssuanceStatus.CALLING,
                Duration.ofSeconds(20));
        when(loans.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(anyLong(), any())).thenReturn(Optional.of(loan));
        TransactionSynchronizationManager.initSynchronization();

        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(2L, "E1012",
                StaffEmploymentStatus.ACTIVE, StaffEmploymentStatus.RESIGNED, 17L, "hc2"));

        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(issuance.getStatus()).isEqualTo(StaffLoanVoucherIssuanceStatus.CALLING);
        verify(issuances, never()).save(any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    @DisplayName("a simulated GetMore loan's flag change says so, for the notifier to keep it apart")
    void simulatedLoanFlagged() {
        ReflectionTestUtils.setField(paidOut, "disbursementReference", "GETMORE-SIM-1207");
        paidOutLoanFound();

        changeTo(StaffEmploymentStatus.ACTIVE, StaffEmploymentStatus.RESIGNED, 21L);

        verify(notifier).notifyAfterCommit(new StaffLoanEmploymentFlagNotifier.Change("SGL-2026-000151", "E1001",
                new BigDecimal("250.00"), "USD", LocalDate.of(2026, 11, 20), null, StaffEmploymentStatus.RESIGNED,
                true));
    }

    @Test
    @DisplayName("a loan already closed, or unknown, cannot be cancelled")
    void cancelRefused() {
        service.cancel(143L, "first");

        assertThatThrownBy(() -> service.cancel(143L, "again")).isInstanceOf(ConflictException.class)
                .hasMessage("Staff loan SGL-2026-000143 is CANCELLED; only a loan awaiting disbursement can be"
                        + " cancelled");
        assertThatThrownBy(() -> service.cancel(9L, "why")).isInstanceOf(NotFoundException.class)
                .hasMessage("Staff loan 9 not found");
    }

    @ParameterizedTest
    @EnumSource(value = StaffEmploymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    @DisplayName("a borrower who stops being ACTIVE has their loan awaiting payout cancelled at once, by the approver")
    void cancelledWhenNoLongerActive(StaffEmploymentStatus to) {
        when(loans.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(anyLong(), any())).thenReturn(Optional.of(loan));

        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(2L, "E1012",
                StaffEmploymentStatus.ACTIVE, to, 17L, "hc2"));

        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(loan.getCancelledBy()).isEqualTo("hc2");
        assertThat(loan.getCancellationReason()).isEqualTo("Employment status changed to " + to
                + " before the loan was paid out (register batch 17)");
        assertThat(audited().getChannelUsed()).isEqualTo("system");
    }

    @Test
    @DisplayName("back to ACTIVE, or no loan awaiting payout: nothing happens")
    void listenerLeavesTheRestAlone() {
        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(2L, "E1012",
                StaffEmploymentStatus.SUSPENDED, StaffEmploymentStatus.ACTIVE, 17L, "hc2"));
        when(loans.findFirstByStaffMemberIdAndStatusInOrderByIdDesc(anyLong(), any())).thenReturn(Optional.empty());
        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(2L, "E1012",
                StaffEmploymentStatus.ACTIVE, StaffEmploymentStatus.RESIGNED, 17L, "hc2"));

        assertThat(loan.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        verify(loans, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = StaffEmploymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    @DisplayName("a paid-out loan is flagged with the status its borrower moved to, audited, and its email queued")
    void paidOutLoanFlagged(StaffEmploymentStatus to) {
        paidOutLoanFound();

        changeTo(StaffEmploymentStatus.ACTIVE, to, 21L);

        assertThat(paidOut.getStatus()).isEqualTo(StaffLoanStatus.DISBURSED);
        StaffLoanEmploymentFlag flag = service.loan(151L).loan().employmentFlag();
        assertThat(flag.employmentStatus()).isEqualTo(to);
        assertThat(flag.action()).isEqualTo(to.hasLeft() ? EmploymentFlagAction.RECOVER_FROM_TERMINAL_BENEFITS
                : EmploymentFlagAction.CREDIT_TO_DECIDE);
        assertThat(flag.flaggedAt()).isEqualTo(NOW);
        assertThat(flag.registerBatchId()).isEqualTo(21L);
        verify(loans).save(paidOut);
        AuditLog audited = audited();
        assertThat(audited.getEventType()).isEqualTo(StaffLoanService.EMPLOYMENT_FLAGGED);
        assertThat(audited.getActorId()).isEqualTo("hc2");
        assertThat(audited.getChannelUsed()).isEqualTo("system");
        assertThat(audited.getDetail()).isEqualTo("reference:SGL-2026-000151;from:ACTIVE;to:" + to + ";action:"
                + flag.action() + ";batch:21");
        verify(notifier).notifyAfterCommit(new StaffLoanEmploymentFlagNotifier.Change("SGL-2026-000151", "E1001",
                new BigDecimal("250.00"), "USD", LocalDate.of(2026, 11, 20), null, to, false));
    }

    @Test
    @DisplayName("a written-off loan is still owed, so it is flagged too")
    void writtenOffLoanFlagged() {
        StaffLoan writtenOff = paidOutLoan(StaffLoanStatus.WRITTEN_OFF);
        when(loans.findByStaffMemberIdInAndStatusIn(any(), any())).thenReturn(List.of(writtenOff));
        when(loans.lockById(151L)).thenReturn(Optional.of(writtenOff));

        changeTo(StaffEmploymentStatus.ACTIVE, StaffEmploymentStatus.TERMINATED, 21L);

        assertThat(writtenOff.getEmploymentFlag()).isEqualTo(StaffEmploymentStatus.TERMINATED);
        verify(loans).findByStaffMemberIdInAndStatusIn(List.of(1L),
                List.of(StaffLoanStatus.DISBURSED, StaffLoanStatus.WRITTEN_OFF));
    }

    @Test
    @DisplayName("a second change moves the flag on, and the email says what it was")
    void flagMovesOn() {
        paidOutLoanFound();
        changeTo(StaffEmploymentStatus.ACTIVE, StaffEmploymentStatus.SUSPENDED, 21L);

        changeTo(StaffEmploymentStatus.SUSPENDED, StaffEmploymentStatus.TERMINATED, 22L);

        assertThat(paidOut.getEmploymentFlag()).isEqualTo(StaffEmploymentStatus.TERMINATED);
        assertThat(paidOut.getEmploymentFlagBatchId()).isEqualTo(22L);
        assertThat(audited().getDetail()).isEqualTo("reference:SGL-2026-000151;from:SUSPENDED;to:TERMINATED;"
                + "action:RECOVER_FROM_TERMINAL_BENEFITS;batch:22");
        verify(notifier).notifyAfterCommit(new StaffLoanEmploymentFlagNotifier.Change("SGL-2026-000151", "E1001",
                new BigDecimal("250.00"), "USD", LocalDate.of(2026, 11, 20), StaffEmploymentStatus.SUSPENDED,
                StaffEmploymentStatus.TERMINATED, false));
    }

    @Test
    @DisplayName("ACTIVE again clears the flag, audited, and whoever was told hears it no longer applies")
    void flagCleared() {
        paidOutLoanFound();
        changeTo(StaffEmploymentStatus.ACTIVE, StaffEmploymentStatus.RESIGNED, 21L);

        changeTo(StaffEmploymentStatus.RESIGNED, StaffEmploymentStatus.ACTIVE, 23L);

        assertThat(paidOut.getEmploymentFlag()).isNull();
        assertThat(paidOut.getEmploymentFlaggedAt()).isNull();
        assertThat(paidOut.getEmploymentFlagBatchId()).isNull();
        assertThat(service.loan(151L).loan().employmentFlag()).isNull();
        assertThat(audited().getEventType()).isEqualTo(StaffLoanService.EMPLOYMENT_FLAG_CLEARED);
        assertThat(audited().getDetail()).isEqualTo("reference:SGL-2026-000151;from:RESIGNED;to:ACTIVE;action:none;"
                + "batch:23");
        verify(notifier).notifyAfterCommit(new StaffLoanEmploymentFlagNotifier.Change("SGL-2026-000151", "E1001",
                new BigDecimal("250.00"), "USD", LocalDate.of(2026, 11, 20), StaffEmploymentStatus.RESIGNED, null,
                false));
    }

    @Test
    @DisplayName("a loan settled by the time it is locked, or back to ACTIVE with no flag, is left alone")
    void unflaggedLoansLeftAlone() {
        StaffLoan repaid = paidOutLoan(StaffLoanStatus.REPAID);
        when(loans.findByStaffMemberIdInAndStatusIn(any(), any())).thenReturn(List.of(paidOut));
        when(loans.lockById(151L)).thenReturn(Optional.of(repaid));
        changeTo(StaffEmploymentStatus.ACTIVE, StaffEmploymentStatus.RESIGNED, 21L);

        when(loans.lockById(151L)).thenReturn(Optional.of(paidOut));
        changeTo(StaffEmploymentStatus.SUSPENDED, StaffEmploymentStatus.ACTIVE, 22L);

        assertThat(repaid.getEmploymentFlag()).isNull();
        assertThat(paidOut.getEmploymentFlag()).isNull();
        verify(loans, never()).save(any());
        verify(audit, never()).record(any());
        verify(notifier, never()).notifyAfterCommit(any());
    }

    @Test
    @DisplayName("flagged=true lists flagged loans, false the rest, and the flag is on each")
    void listFlagged() {
        paidOutLoanFound();
        changeTo(StaffEmploymentStatus.ACTIVE, StaffEmploymentStatus.RESIGNED, 21L);
        when(loans.findAll(org.mockito.ArgumentMatchers.<org.springframework.data.jpa.domain.Specification<StaffLoan>>any(),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(paidOut)));

        StaffLoanResponse listed = service.loans(null, null, true,
                org.springframework.data.domain.PageRequest.of(0, 20)).getContent().getFirst();

        assertThat(listed.employmentFlag().action()).isEqualTo(EmploymentFlagAction.RECOVER_FROM_TERMINAL_BENEFITS);
    }

    private void paidOutLoanFound() {
        when(loans.findByStaffMemberIdInAndStatusIn(any(), any())).thenReturn(List.of(paidOut));
    }

    private void changeTo(StaffEmploymentStatus from, StaffEmploymentStatus to, Long batchId) {
        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(1L, "E1001", from, to, batchId, "hc2"));
    }

    @Test
    @DisplayName("the detail carries the agreement, intact while it matches its seal and flagged when it does not")
    void detail() {
        StaffLoanAgreement unsealed = StaffLoanAgreement.builder().id(1L).staffLoanId(143L)
                .instrumentType(InstrumentType.STAFF_GROCERY_LOAN_AGREEMENT).templateVersion(1)
                .title("Staff Grocery Loan Agreement").content("I borrow USD 300.00.")
                .contentSha256(AuditService.sha256Hex("I borrow USD 300.00.")).acceptedBy("borrower:E1012")
                .acceptedAt(LocalDateTime.of(2026, 10, 1, 7, 10, 41, 123_456_000)).deviceId("device-1")
                .ipAddress("10.0.4.17").authenticationMethod("pin").assertionId("mw-7c1d9e2a").build();
        StaffLoanAgreement sealed = unsealed.toBuilder()
                .evidenceSha256(StaffLoanJourneyService.evidenceSha256(unsealed)).build();
        when(agreements.findByStaffLoanId(143L)).thenReturn(Optional.of(sealed));

        StaffLoanDetailResponse detail = service.loan(143L);

        assertThat(detail.loan().reference()).isEqualTo("SGL-2026-000143");
        assertThat(detail.loan().inArrears()).isFalse();
        assertThat(detail.agreement().intact()).isTrue();
        assertThat(detail.agreement().authenticationMethod()).isEqualTo("pin");

        when(agreements.findByStaffLoanId(143L)).thenReturn(Optional.of(sealed.toBuilder()
                .content("I borrow USD 30.00.").build()));
        assertThat(service.loan(143L).agreement().intact()).isFalse();
        when(agreements.findByStaffLoanId(143L)).thenReturn(Optional.of(sealed.toBuilder()
                .deviceId("another").build()));
        assertThat(service.loan(143L).agreement().intact()).isFalse();
    }

    private AuditLog audited() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getValue().build();
    }
}
