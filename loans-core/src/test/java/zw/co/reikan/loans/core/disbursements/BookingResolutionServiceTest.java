package zw.co.reikan.loans.core.disbursements;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.exception.ConflictException;
import zw.co.reikan.loans.core.exception.NotFoundException;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.DeductionCancellationStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A booking held as possibly-landed is only ever failed by an operator who has InnBucks'
 * confirmation. Recording it must leave the loan exactly where a booking InnBucks refused outright
 * does — FAILED/REFUSED and flagged — so the recovery payout and the cancellation queue treat it
 * the same, and the operator's note is pinned by hash rather than copied into the audit trail.
 */
class BookingResolutionServiceTest {

    private static final String NOTE = "InnBucks ops (T. Moyo) confirmed no loan under 000000042, ticket INC-7781";

    private LoanRepository loanRepository;
    private AuditService auditService;
    private BookingResolutionService service;
    private Loan loan;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        auditService = mock(AuditService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("ops.admin");
        service = new BookingResolutionService(loanRepository,
                new DeductionCancellationService(loanRepository, auditService, authService), auditService, authService);

        loan = Loan.builder()
                .loanAccountStatus(LoanAccountStatus.CREATED)
                .disbursementStatus(LoanDisbursementStatus.PENDING)
                .bookingFailureKind(BookingFailureKind.AMBIGUOUS)
                .bookingNotFoundAt(LocalDateTime.of(2026, 9, 29, 8, 0))
                .batchNumber("BATCH-20260901-07")
                .ecNumber("1234567A")
                .build();
        loan.setId(42L);
        when(loanRepository.findByIdForUpdate(42L)).thenReturn(Optional.of(loan));
        when(loanRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private List<AuditLog> audited() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(AuditLog.AuditLogBuilder::build).toList();
    }

    @Test
    @DisplayName("confirmed not booked: FAILED/REFUSED like an outright refusal, and its live deduction flagged")
    void confirmedNotBookedEndsLikeARefusal() {
        HeldBookingDto result = service.confirmNotBooked(42L, "  " + NOTE + "  ");

        assertThat(loan.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.FAILED);
        assertThat(loan.getDisbursementStatus()).isEqualTo(LoanDisbursementStatus.FAILED);
        assertThat(loan.getBookingFailureKind()).isEqualTo(BookingFailureKind.REFUSED);
        assertThat(loan.getDisbursementStatusMessage())
                .startsWith("InnBucks confirmed no loan was booked (recorded by ops.admin): InnBucks ops");
        assertThat(loan.getDeductionCancellationStatus()).isEqualTo(DeductionCancellationStatus.REQUIRED);
        assertThat(loan.getDeductionCancellationReason()).isEqualTo("BOOKING_FAILED");
        assertThat(result.getLoanAccountStatus()).isEqualTo(LoanAccountStatus.FAILED);
        verify(loanRepository).save(loan);
    }

    @Test
    @DisplayName("audited with the operator and the earlier state; the note by hash only")
    void auditedWithTheOperator() {
        service.confirmNotBooked(42L, NOTE);

        assertThat(audited()).extracting(AuditLog::getEventType)
                .containsExactly("DEDUCTION_CANCELLATION_REQUIRED", "INNBUCKS_BOOKING_CONFIRMED_NOT_BOOKED");
        AuditLog row = audited().get(1);
        assertThat(row.getActorId()).isEqualTo("ops.admin");
        assertThat(row.getDetail()).contains("bookingFailureKindBefore=AMBIGUOUS", "notFoundSince=2026-09-29T08:00")
                .doesNotContain("INC-7781");
        assertThat(row.getPayloadHash()).isEqualTo(AuditService.sha256Hex(NOTE));
    }

    @Test
    @DisplayName("refused (409) for a loan with no booking awaiting InnBucks — nothing changes")
    void refusedUnlessHeldAsBooked() {
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);

        assertThatThrownBy(() -> service.confirmNotBooked(42L, NOTE))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Loan 42 has no booking awaiting InnBucks (account status CREATED, disbursement status SUCCESS)");
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("an unknown loan is a 404")
    void unknownLoanIsNotFound() {
        when(loanRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmNotBooked(7L, NOTE))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Loan 7 not found");
    }

    @Test
    @DisplayName("the held list puts loans InnBucks reports missing first, then oldest first")
    void heldListOrdersReportedMissingFirst() {
        Loan older = Loan.builder().loanAccountStatus(LoanAccountStatus.CREATED)
                .disbursementStatus(LoanDisbursementStatus.PENDING).build();
        older.setId(10L);
        Loan newer = Loan.builder().loanAccountStatus(LoanAccountStatus.CREATED)
                .disbursementStatus(LoanDisbursementStatus.PENDING).build();
        newer.setId(11L);
        when(loanRepository.findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING)).thenReturn(List.of(newer, loan, older));

        assertThat(service.findHeld()).extracting(HeldBookingDto::getId).containsExactly(42L, 10L, 11L);
    }
}
