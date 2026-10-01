package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatusChanged;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
 * theirs cancelled at once, and the agreement is shown with its seal checked. The clock stands at 1 October 2026,
 * 11:02:17 in Harare.
 */
class StaffLoanServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 9, 2, 17);

    private final StaffLoanRepository loans = mock(StaffLoanRepository.class);
    private final StaffLoanAgreementRepository agreements = mock(StaffLoanAgreementRepository.class);
    private final AuthService authService = mock(AuthService.class);
    private final AuditService audit = mock(AuditService.class);
    private final StaffLoanService service = new StaffLoanService(loans, agreements,
            new StaffLoanPolicy(new StaffLoanProperties(), new VoucherProperties()), authService, audit,
            new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-10-01T09:02:17Z"), ZoneOffset.UTC)));
    private final StaffLoan loan = StaffLoan.builder().id(143L).reference("SGL-2026-000143").staffMemberId(2L)
            .offerId(31L).employeeNumber("E1012").fullName("Chipo Banda").msisdn("263773456789").grade("C4")
            .amount(new BigDecimal("300.00")).currency("USD").interestRate(BigDecimal.ZERO)
            .totalRepayable(new BigDecimal("300.00")).dueDate(LocalDate.of(2026, 11, 20))
            .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS)
            .status(StaffLoanStatus.AWAITING_DISBURSEMENT).acceptedAt(LocalDateTime.of(2026, 10, 1, 7, 10, 41))
            .build();

    {
        when(loans.lockById(143L)).thenReturn(Optional.of(loan));
        when(loans.findById(143L)).thenReturn(Optional.of(loan));
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
