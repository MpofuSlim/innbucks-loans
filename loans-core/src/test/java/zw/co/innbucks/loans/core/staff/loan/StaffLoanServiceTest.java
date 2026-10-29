package zw.co.innbucks.loans.core.staff.loan;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatusChanged;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationService;
import zw.co.innbucks.loans.core.staff.notification.StaffNotificationTemplate;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private final StaffNotificationService notifications = mock(StaffNotificationService.class);
    private final StaffLoanService service = new StaffLoanService(loans, agreements,
            new StaffLoanPolicy(new StaffLoanProperties(), new VoucherProperties()), authService, audit,
            new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-10-01T09:02:17Z"), ZoneOffset.UTC)), notifier,
            notifications);
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
        assertThat(loan.getPayoutStatus()).as("a voucher loan has no payout").isNull();
        verifyNoInteractions(notifications);
    }

    /** Chipo's loan, accepted to be paid in cash into her wallet, its payout {@code payout}. */
    private StaffLoan cashLoan(StaffLoanPayoutStatus payout) {
        StaffLoan cash = StaffLoan.builder().id(144L).reference("SGL-2026-000144").staffMemberId(2L)
                .offerId(32L).employeeNumber("E1012").fullName("Chipo Banda").msisdn("263773456789").grade("C4")
                .amount(new BigDecimal("150.00")).currency("USD").interestRate(BigDecimal.ZERO)
                .totalRepayable(new BigDecimal("150.00")).dueDate(LocalDate.of(2026, 11, 20))
                .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS)
                .status(StaffLoanStatus.AWAITING_DISBURSEMENT).acceptedAt(LocalDateTime.of(2026, 10, 1, 7, 10, 41))
                .merchant(getMore()).payoutMethod(StaffLoanPayoutMethod.CASH)
                .payoutDestination(StaffLoanPayoutDestination.VEENGU_WALLET).payoutAccount("263773456789")
                .payoutStatus(payout).payoutClaimedAt(payout == StaffLoanPayoutStatus.SENDING ? NOW : null).build();
        when(loans.lockById(144L)).thenReturn(Optional.of(cash));
        return cash;
    }

    @ParameterizedTest
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"READY", "REFUSED"})
    @DisplayName("Credit cancels a cash loan nothing was booked for: WITHDRAWN, and the borrower told it was not paid")
    void cancelCash(StaffLoanPayoutStatus payout) {
        StaffLoan cash = cashLoan(payout);

        service.cancel(144L, "Accepted in error");

        assertThat(cash.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(cash.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.WITHDRAWN);
        verify(notifications).notifyLoan(cash, StaffNotificationTemplate.LOAN_NOT_PAID_OUT,
                "InnBucks did not pay out your Staff Grocery Loan SGL-2026-000144, so it is cancelled and nothing is"
                        + " owed. Log in to the InnBucks app for details.");
        assertThat(audited().getEventType()).isEqualTo(StaffLoanService.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"SENDING", "BOOKED", "IN_DOUBT"})
    @DisplayName("a cash loan the bank may already have paid cannot be cancelled: 409, nothing changed")
    void cancelRefusedInFlight(StaffLoanPayoutStatus payout) {
        StaffLoan cash = cashLoan(payout);

        assertThatThrownBy(() -> service.cancel(144L, "Accepted in error")).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("Staff loan SGL-2026-000144 is being paid out (payout " + payout + ")")
                .hasMessageContaining("/lending/v1/staff-loans/144/payout/not-booked");

        assertThat(cash.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(cash.getPayoutStatus()).isEqualTo(payout);
        verify(loans, never()).save(any());
        verifyNoInteractions(notifications, audit);
    }

    @Test
    @DisplayName("a paid cash loan cannot be cancelled either: it is DISBURSED")
    void cancelRefusedPaid() {
        StaffLoan cash = cashLoan(StaffLoanPayoutStatus.READY);
        cash.claimPayout(NOW);
        cash.payoutBooked(NOW, null);
        cash.paidOut(NOW, null);

        assertThatThrownBy(() -> service.cancel(144L, "why")).isInstanceOf(ConflictException.class)
                .hasMessageContaining("is DISBURSED");
        verifyNoInteractions(notifications);
    }

    @Test
    @DisplayName("a leaver's cash loan nothing was booked for is cancelled and they are told; one in flight is neither"
            + " cancelled nor flagged, only logged and audited")
    void leaverWithACashLoan() {
        StaffLoan ready = cashLoan(StaffLoanPayoutStatus.READY);
        when(loans.findIdsByStaffMemberIdAndStatusIn(2L, List.of(StaffLoanStatus.AWAITING_DISBURSEMENT)))
                .thenReturn(List.of(144L));

        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(2L, "E1012", StaffEmploymentStatus.ACTIVE,
                StaffEmploymentStatus.RESIGNED, 17L, "hc2"));

        assertThat(ready.getStatus()).isEqualTo(StaffLoanStatus.CANCELLED);
        assertThat(ready.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.WITHDRAWN);
        verify(notifications).notifyLoan(eq(ready), eq(StaffNotificationTemplate.LOAN_NOT_PAID_OUT), any());

        org.mockito.Mockito.clearInvocations(loans, audit, notifications);
        StaffLoan booked = cashLoan(StaffLoanPayoutStatus.BOOKED);
        service.onEmploymentStatusChanged(new StaffEmploymentStatusChanged(2L, "E1012", StaffEmploymentStatus.ACTIVE,
                StaffEmploymentStatus.TERMINATED, 18L, "hc2"));

        assertThat(booked.getStatus()).isEqualTo(StaffLoanStatus.AWAITING_DISBURSEMENT);
        assertThat(booked.getPayoutStatus()).isEqualTo(StaffLoanPayoutStatus.BOOKED);
        assertThat(booked.getEmploymentFlag()).isNull();
        verify(loans, never()).save(any());
        verifyNoInteractions(notifications);
        AuditLog audited = audited();
        assertThat(audited.getEventType()).isEqualTo(StaffLoanService.PAYOUT_LEAVER_IN_FLIGHT);
        assertThat(audited.getDetail()).isEqualTo("reference:SGL-2026-000144;payout:BOOKED;to:TERMINATED;batch:18");
    }

    @Test
    @DisplayName("a cash loan paid out to a borrower who left meanwhile is flagged, with the batch that moved them")
    void flaggedAtPayout() {
        service.flagEmploymentAtPayout(paidOut, StaffEmploymentStatus.RESIGNED, "staff-loan-payout-status", 17L);

        assertThat(paidOut.getEmploymentFlag()).isEqualTo(StaffEmploymentStatus.RESIGNED);
        assertThat(paidOut.getEmploymentFlagBatchId()).isEqualTo(17L);
        assertThat(audited().getActorId()).isEqualTo("staff-loan-payout-status");
        assertThat(audited().getDetail()).isEqualTo("reference:SGL-2026-000151;from:ACTIVE;to:RESIGNED;"
                + "action:RECOVER_FROM_TERMINAL_BENEFITS;batch:17");
        verify(notifier).notifyAfterCommit(any());

        assertThatThrownBy(() -> service.flagEmploymentAtPayout(loan, StaffEmploymentStatus.RESIGNED, "x", 17L))
                .isInstanceOf(IllegalStateException.class);
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
        when(loans.findIdsByStaffMemberIdAndStatusIn(2L, List.of(StaffLoanStatus.AWAITING_DISBURSEMENT)))
                .thenReturn(List.of(143L));

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
        when(loans.findIdsByStaffMemberIdAndStatusIn(anyLong(), any())).thenReturn(List.of());
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
                new BigDecimal("250.00"), "USD", LocalDate.of(2026, 11, 20), null, to));
    }

    @Test
    @DisplayName("a written-off loan is still owed, so it is flagged too")
    void writtenOffLoanFlagged() {
        StaffLoan writtenOff = paidOutLoan(StaffLoanStatus.WRITTEN_OFF);
        paidOutLoanFound();
        when(loans.lockById(151L)).thenReturn(Optional.of(writtenOff));

        changeTo(StaffEmploymentStatus.ACTIVE, StaffEmploymentStatus.TERMINATED, 21L);

        assertThat(writtenOff.getEmploymentFlag()).isEqualTo(StaffEmploymentStatus.TERMINATED);
        verify(loans).findIdsByStaffMemberIdAndStatusIn(1L,
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
                StaffEmploymentStatus.TERMINATED));
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
                new BigDecimal("250.00"), "USD", LocalDate.of(2026, 11, 20), StaffEmploymentStatus.RESIGNED, null));
    }

    @Test
    @DisplayName("a loan settled by the time it is locked, or back to ACTIVE with no flag, is left alone")
    void unflaggedLoansLeftAlone() {
        StaffLoan repaid = paidOutLoan(StaffLoanStatus.REPAID);
        paidOutLoanFound();
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

        StaffLoanResponse listed = service.loans(null, null, true, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20)).getContent().getFirst();

        assertThat(listed.employmentFlag().action()).isEqualTo(EmploymentFlagAction.RECOVER_FROM_TERMINAL_BENEFITS);
    }

    @Test
    @DisplayName("the list filters on the payout's status and on how loans are paid out, each only when asked")
    @SuppressWarnings("unchecked")
    void listByPayout() {
        ArgumentCaptor<Specification<StaffLoan>> filter = ArgumentCaptor.forClass(Specification.class);
        when(loans.findAll(filter.capture(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        Root<StaffLoan> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> payoutStatus = mock(Path.class);
        Path<Object> payoutMethod = mock(Path.class);
        when(root.get("payoutStatus")).thenReturn(payoutStatus);
        when(root.get("payoutMethod")).thenReturn(payoutMethod);

        service.loans(null, null, null, StaffLoanPayoutStatus.IN_DOUBT, StaffLoanPayoutMethod.CASH,
                PageRequest.of(0, 20));
        filter.getValue().toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb).equal(payoutStatus, StaffLoanPayoutStatus.IN_DOUBT);
        verify(cb).equal(payoutMethod, StaffLoanPayoutMethod.CASH);

        Root<StaffLoan> unfiltered = mock(Root.class);
        service.loans(null, null, null, null, null, PageRequest.of(0, 20));
        filter.getValue().toPredicate(unfiltered, mock(CriteriaQuery.class), mock(CriteriaBuilder.class));
        verify(unfiltered, never()).get("payoutStatus");
        verify(unfiltered, never()).get("payoutMethod");
    }

    @Test
    @DisplayName("a cash loan as the portal sees it: where it is paid, by the last four digits, and where its payout"
            + " stands")
    void cashLoanAsListed() {
        StaffLoan cash = cashLoan(StaffLoanPayoutStatus.READY);
        cash.claimPayout(NOW);
        cash.payoutInDoubt("The bank answered HTTP 504", "{\"responseCode\":\"999\"}");
        when(loans.findById(144L)).thenReturn(Optional.of(cash));

        StaffLoanResponse listed = service.summary(144L);

        assertThat(listed.reference()).isEqualTo("SGL-2026-000144");
        assertThat(listed.payoutMethod()).isEqualTo(StaffLoanPayoutMethod.CASH);
        assertThat(listed.payoutDestination()).isEqualTo(StaffLoanPayoutDestination.VEENGU_WALLET);
        assertThat(listed.payoutAccount()).isEqualTo("****6789");
        assertThat(listed.payoutStatus()).isEqualTo(StaffLoanPayoutStatus.IN_DOUBT);
        assertThat(listed.payoutMessage()).isEqualTo("The bank answered HTTP 504");
        assertThat(listed.payoutLastAnswer()).isEqualTo("{\"responseCode\":\"999\"}");
        assertThat(listed.payoutClaimedAt()).isNull();
        assertThat(listed.payoutBankSeenAt()).isNull();
        assertThat(listed.merchantCode()).as("recorded, not used").isEqualTo("getmore-groceries");
        assertThat(listed.toString()).doesNotContain("263773456789".substring(0, 8));
    }

    @Test
    @DisplayName("a voucher loan carries no payout field at all, not even its method: it reads as it did before cash"
            + " payouts; an unknown loan is not found")
    void voucherLoanAsListed() {
        StaffLoanResponse listed = service.summary(143L);

        assertThat(listed).extracting(StaffLoanResponse::payoutMethod, StaffLoanResponse::payoutDestination,
                StaffLoanResponse::payoutAccount, StaffLoanResponse::payoutStatus, StaffLoanResponse::payoutMessage,
                StaffLoanResponse::payoutApplyAnswer, StaffLoanResponse::payoutLastAnswer,
                StaffLoanResponse::payoutCheckedAt, StaffLoanResponse::payoutDeclinedAt,
                StaffLoanResponse::bankLoanAccount).containsOnlyNulls();
        JsonMapper json = JsonMapper.builder().build();
        assertThat(json.readTree(json.writeValueAsString(listed)).propertyNames())
                .as("no payout key on the wire").noneMatch(name -> name.startsWith("payout")
                        || name.equals("bankLoanAccount"))
                .contains("arrearsOverrideId", "employmentFlag", "cancellationReason");
        assertThatThrownBy(() -> service.summary(999L)).isInstanceOf(NotFoundException.class)
                .hasMessage("Staff loan 999 not found");
    }

    private void paidOutLoanFound() {
        when(loans.findIdsByStaffMemberIdAndStatusIn(eq(1L),
                eq(List.of(StaffLoanStatus.DISBURSED, StaffLoanStatus.WRITTEN_OFF)))).thenReturn(List.of(151L));
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
