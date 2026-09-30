package zw.co.innbucks.loans.core.disbursements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.notice.LoanNotice;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;

import java.util.Comparator;
import java.util.List;

/**
 * The way out for a loan held as booked that InnBucks never paid. A booking whose outcome was
 * unknown is held CREATED/PENDING for the inquiry job, which settles it only when InnBucks reports
 * it paid; nothing clears one that never landed, because what InnBucks' inquiry returns for a
 * missing loan is unconfirmed and guessing wrong would open a paid loan to a second payout. So an
 * operator confirms it with InnBucks and records it here, which is what makes the loan eligible
 * for the recovery payout.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingResolutionService {

    static final String CONFIRMED_NOT_BOOKED = "INNBUCKS_BOOKING_CONFIRMED_NOT_BOOKED";
    static final String PORTAL_CHANNEL = "admin-portal";

    private final LoanRepository loanRepository;
    private final DeductionCancellationService deductionCancellationService;
    private final AuditService auditService;
    private final AuthService authService;
    private final LoanNotificationService loanNotificationService;

    /** Loans held as booked without a confirmed payout: the ones InnBucks reports missing first, then oldest. */
    @Transactional(readOnly = true)
    public List<HeldBookingResponse> findHeld() {
        return loanRepository.findByLoanAccountStatusAndDisbursementStatus(
                        LoanAccountStatus.CREATED, LoanDisbursementStatus.PENDING)
                .stream()
                .sorted(Comparator.comparing((Loan loan) -> loan.getBookingNotFoundAt() == null)
                        .thenComparing(Loan::getId))
                .map(HeldBookingResponse::from)
                .toList();
    }

    /**
     * Records InnBucks' confirmation that it booked nothing under this loan's reference. Row-locked,
     * so it cannot race the inquiry job settling the same loan. The loan ends exactly as a booking
     * InnBucks refused outright does: FAILED/REFUSED, open to the recovery payout, with its live
     * Ndasenda deduction flagged for cancellation.
     */
    @Transactional
    public HeldBookingResponse confirmNotBooked(Long loanId, String note) {
        Loan loan = loanRepository.findByIdForUpdate(loanId)
                .orElseThrow(() -> new NotFoundException("Loan " + loanId + " not found"));

        if (loan.getLoanAccountStatus() != LoanAccountStatus.CREATED
                || loan.getDisbursementStatus() != LoanDisbursementStatus.PENDING) {
            throw new ConflictException(String.format(
                    "Loan %d has no booking awaiting InnBucks (account status %s, disbursement status %s)",
                    loanId, loan.getLoanAccountStatus(), loan.getDisbursementStatus()));
        }

        String username = authService.getLoggedInUsername();
        String cleanNote = StringUtils.strip(note);
        BookingFailureKind kindBefore = loan.getBookingFailureKind();

        loan.setLoanAccountStatus(LoanAccountStatus.FAILED);
        loan.setDisbursementStatus(LoanDisbursementStatus.FAILED);
        loan.setBookingFailureKind(BookingFailureKind.REFUSED);
        loan.setDisbursementStatusMessage(StringUtils.abbreviate(
                "InnBucks confirmed no loan was booked (recorded by " + username + "): " + cleanNote, 255));
        // Nothing paid the loan, but Ndasenda accepted its deduction: the flag a refused booking raises.
        deductionCancellationService.markRequired(loan, DeductionCancellationService.REASON_BOOKING_FAILED,
                username, PORTAL_CHANNEL);
        Loan saved = loanRepository.save(loan);
        // It may yet be paid by a recovery payout: the applicant hears of a delay once this commits (FR-SSB-016).
        loanNotificationService.notify(saved, LoanNotice.PAYOUT_DELAYED);

        log.warn("InnBucks booking of loan {} reference {} recorded as never landed by {} (was {}, reported"
                        + " missing since {}) - now eligible for a recovery payout (audited)",
                loan.getId(), loan.getReference(), username, kindBefore, loan.getBookingNotFoundAt());
        audit(loan, username, kindBefore, cleanNote);
        return HeldBookingResponse.from(saved);
    }

    private void audit(Loan loan, String username, BookingFailureKind kindBefore, String note) {
        try {
            // The note is free text: pinned by its hash, not copied into the audit trail.
            auditService.record(AuditLog.builder()
                    .eventType(CONFIRMED_NOT_BOOKED)
                    .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                    .actorId(username).channelUsed(PORTAL_CHANNEL)
                    .stateTransitionDelta("{\"from\":\"CREATED/PENDING\",\"to\":\"FAILED/FAILED\"}")
                    .detail("reference=" + loan.getReference() + " bookingFailureKindBefore=" + kindBefore
                            + " notFoundSince=" + loan.getBookingNotFoundAt())
                    .payloadHash(AuditService.sha256Hex(note))
                    .correlationId(loan.getReference()));
        } catch (Exception ex) {
            log.error("Audit of loan {} ({}) failed", loan.getId(), CONFIRMED_NOT_BOOKED, ex);
        }
    }
}
