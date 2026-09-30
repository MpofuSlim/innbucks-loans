package zw.co.innbucks.loans.core.notice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanRepository;
import zw.co.innbucks.loans.core.loan.LoanSpecification;

import java.util.List;
import java.util.UUID;

/**
 * Tells the applicant each stage their application reaches (FR-SSB-016), and keeps what they were told.
 *
 * <p>Raised inside a transaction, a notice is sent once it commits: an applicant is never told of a stage
 * that was then rolled back. Raised outside one, it is sent at once. Either way it is sent off the caller's
 * thread and never throws, and every attempt is recorded, sent or not, so an officer can see what the
 * applicant was told and a failed message is visible rather than lost.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanNotificationService {

    private final LoanNotificationSender sender;
    private final LoanNotificationRepository notificationRepository;
    private final LoanRepository loanRepository;

    /** Tells the applicant, in the notice's own words, once the current transaction commits. */
    public void notify(Loan loan, LoanNotice notice) {
        String message;
        try {
            message = notice.textFor(loan);
        } catch (RuntimeException ex) {
            log.error("Loan {} {} notice could not be worded", loan.getId(), notice, ex);
            return;
        }
        notify(loan, notice, message);
    }

    /**
     * Tells the applicant, in these words, once the current transaction commits. Nothing here may throw: it is
     * called inside the transactions that record lodgements and payouts, and a failure to tell the applicant
     * must never roll one back.
     */
    public void notify(Loan loan, LoanNotice notice, String message) {
        try {
            OutgoingNotice outgoing = new OutgoingNotice(loan.getId(), notice, loan.getMobileNumber(), message,
                    "LOANS-SMS-" + UUID.randomUUID());
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dispatch(outgoing);
                    }
                });
            } else {
                dispatch(outgoing);
            }
        } catch (RuntimeException ex) {
            log.error("Loan {} {} notice could not be raised", loan.getId(), notice, ex);
        }
    }

    /**
     * Every message the applicant was sent about this loan, oldest first.
     *
     * @throws NotFoundException no such loan, or not one the caller may read
     */
    @Transactional(readOnly = true)
    public List<LoanNotificationResponse> history(Long loanId, LoanReadScope scope) {
        boolean readable = scope.platformWide()
                ? loanRepository.existsById(loanId)
                : loanRepository.exists(LoanSpecification.readableBy(loanId, scope));
        if (!readable) {
            throw new NotFoundException("Loan " + loanId + " not found");
        }
        return notificationRepository.findByLoanIdOrderByIdAsc(loanId).stream()
                .map(LoanNotificationResponse::of)
                .toList();
    }

    private void dispatch(OutgoingNotice outgoing) {
        try {
            sender.deliver(outgoing);
        } catch (RuntimeException ex) {
            // The executor refused it (shutting down, or saturated): nothing was sent or recorded.
            log.error("Loan {} {} notice could not be queued for sending", outgoing.loanId(), outgoing.notice(), ex);
        }
    }
}
