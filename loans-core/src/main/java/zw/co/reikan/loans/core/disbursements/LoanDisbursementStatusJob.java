package zw.co.reikan.loans.core.disbursements;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.ledger.DisbursementLedger;
import zw.co.reikan.loans.core.loan.DeductionCancellationService;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.time.LocalDateTime;

@Service
@Slf4j
@Profile("scheduled-tasks")
public class LoanDisbursementStatusJob {

    static final String SYSTEM_ACTOR = "loan-disbursement-status-job";
    static final String BOOKING_NOT_FOUND = "INNBUCKS_BOOKING_NOT_FOUND";
    static final String BOOKING_FOUND = "INNBUCKS_BOOKING_FOUND";

    private final DisbursementService disbursementService;
    private final LoanRepository loanRepository;
    private final NotificationService notificationService;
    private final DeductionCancellationService deductionCancellationService;
    private final AuditService auditService;
    private final DisbursementLedger disbursementLedger;
    private final TransactionTemplate transactionTemplate;

    public LoanDisbursementStatusJob(DisbursementService disbursementService, LoanRepository loanRepository,
                                     NotificationService notificationService,
                                     DeductionCancellationService deductionCancellationService,
                                     AuditService auditService, DisbursementLedger disbursementLedger,
                                     PlatformTransactionManager transactionManager) {
        this.disbursementService = disbursementService;
        this.loanRepository = loanRepository;
        this.notificationService = notificationService;
        this.deductionCancellationService = deductionCancellationService;
        this.auditService = auditService;
        this.disbursementLedger = disbursementLedger;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Processes loans with PENDING disbursement status.
     * Runs at a configurable rate (default: every 3 minutes).
     */
    @Scheduled(fixedRateString = "${innbucks.loan-disbursement-status-check-rate:180000}")
    public void processLoanDisbursementStatus() {
        log.info("Starting LoanDisbursementStatusJob...");

        var loans = loanRepository.findByLoanAccountStatusAndDisbursementStatus(
                LoanAccountStatus.CREATED,
                LoanDisbursementStatus.PENDING
        );

        log.info("Found {} loans with CREATED account status and PENDING disbursement status", loans.size());

        loans.forEach(this::checkLoanDisbursementStatus);

        log.info("Completed LoanDisbursementStatusJob");
    }

    private void checkLoanDisbursementStatus(Loan loan) {
        boolean paid = false;
        try {
            log.debug("Checking disbursement status for loan: {}", loan.getId());
            LoanDisbursementStatusResponse response = disbursementService.checkLoanDisbursementStatus(loan);

            if (response == null) {
                log.warn("Received null response when checking disbursement status for loan: {}", loan.getId());
                loan.setDisbursementStatusMessage("Status check returned null response");
            } else if (response.isNotFound()) {
                handleNotFound(loan);
            } else if (response.isSuccess()) {
                noteFoundAgain(loan, response);
                paid = handleSuccessfulStatusCheck(loan, response);
            } else {
                handleFailedStatusCheck(loan, response);
            }
        } catch (Exception ex) {
            handleStatusCheckException(loan, ex);
        }

        if (paid) {
            recordPaid(loan);
        } else {
            loanRepository.save(loan);
        }
    }

    /**
     * The payout and its ledger posting commit together, so no loan is ever recorded as paid with
     * nothing in the ledger, and the customer is told only once both have. If they cannot be saved
     * nothing is: the loan is still waiting on InnBucks, and the next run asks again.
     */
    private void recordPaid(Loan loan) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                disbursementLedger.recordPayout(loan, SYSTEM_ACTOR);
                loanRepository.save(loan);
            });
        } catch (RuntimeException ex) {
            log.error("Loan {} [{}] was reported paid by InnBucks but could not be recorded; the next run retries",
                    loan.getId(), loan.getReference(), ex);
            return;
        }
        notifyCustomer(loan);
    }

    /** @return whether InnBucks reports the loan paid, to be recorded with its ledger posting */
    private boolean handleSuccessfulStatusCheck(Loan loan, LoanDisbursementStatusResponse response) {
        log.info("Loan disbursement status check successful for loan: {}", loan.getId());

        LoanDisbursementStatus newStatus = response.getStatus();
        loan.setDisbursementStatus(newStatus);

        if (newStatus == LoanDisbursementStatus.SUCCESS) {
            // Loan has been successfully disbursed
            loan.setDateDisbursed(LocalDateTime.now());
            return true;
        } else if (newStatus == LoanDisbursementStatus.FAILED) {
            // Loan disbursement has failed
            loan.setDisbursementStatusMessage(response.getResponseDescription());
            // InnBucks' own answer, so definitive; flagged here rather than only by the saga, which
            // skips terminal sagas and loans older than 30 days. Saved with the loan below.
            deductionCancellationService.markRequired(loan, DeductionCancellationService.REASON_BOOKING_FAILED,
                    SYSTEM_ACTOR, "system");
        }
        // If still PENDING, do nothing special
        return false;
    }

    private void notifyCustomer(Loan loan) {
        try {
            // A merchant loan paid the merchant: the customer is told where to collect the goods,
            // not that the money reached their own wallet.
            final String message = DisbursementService.disbursementSms(loan);
            notificationService.sendSms(loan.getMobileNumber(), message);
            log.info("Notification sent successfully to customer: {}", loan.getMobileNumber());
        } catch (Exception ex) {
            log.error("Failed to send notification to customer: {}, but loan disbursement was successful",
                    loan.getMobileNumber(), ex);
            // Notification failure shouldn't affect the loan disbursement status
        }
    }

    /**
     * InnBucks says it holds no loan under this reference. That is NOT acted on: what the inquiry
     * returns for a missing loan is unconfirmed by InnBucks, and a booking can still land after a
     * timeout, so failing the loan here could open a paid loan to a second payout. It is reported
     * once (ERROR + audit) and the loan waits for an operator, who confirms with InnBucks and
     * resolves it through POST /api/loans/{id}/booking/confirm-not-booked.
     */
    private void handleNotFound(Loan loan) {
        loan.setDisbursementStatusMessage("InnBucks reports no loan under reference " + loan.getReference()
                + ". Confirm with InnBucks; if it never landed, resolve it as not booked");
        if (loan.getBookingNotFoundAt() != null) {
            return;
        }
        loan.setBookingNotFoundAt(LocalDateTime.now());
        log.error("INNBUCKS BOOKING NOT FOUND: loan {} reference {} is held as booked but InnBucks reports no loan"
                        + " under it - confirm with InnBucks, then resolve with POST /api/loans/{}/booking/confirm-not-booked"
                        + " if it never landed (audited)",
                loan.getId(), loan.getReference(), loan.getId());
        audit(BOOKING_NOT_FOUND, loan, "bookingFailureKind=" + loan.getBookingFailureKind());
    }

    /** A loan reported missing that InnBucks now answers for: the booking landed after all. */
    private void noteFoundAgain(Loan loan, LoanDisbursementStatusResponse response) {
        if (loan.getBookingNotFoundAt() == null || !response.isApproved()) {
            return;
        }
        log.warn("InnBucks now reports loan {} reference {}, which it had reported missing since {}",
                loan.getId(), loan.getReference(), loan.getBookingNotFoundAt());
        audit(BOOKING_FOUND, loan, "notFoundSince=" + loan.getBookingNotFoundAt());
        loan.setBookingNotFoundAt(null);
    }

    private void audit(String eventType, Loan loan, String detail) {
        try {
            auditService.record(AuditLog.builder()
                    .eventType(eventType)
                    .entityType("LOAN").entityId(String.valueOf(loan.getId()))
                    .actorId(SYSTEM_ACTOR).channelUsed("system")
                    .detail(detail + " reference=" + loan.getReference())
                    .correlationId(loan.getReference()));
        } catch (Exception ex) {
            // The ERROR line above is the evidence; an audit fault must not stop the other loans.
            log.error("Audit of loan {} ({}) failed", loan.getId(), eventType, ex);
        }
    }

    private void handleFailedStatusCheck(Loan loan, LoanDisbursementStatusResponse response) {
        log.info("Loan disbursement status check failed for loan: {}", loan.getId());

        // Keep the status as PENDING, we'll try again next time
        // Just update the message
        loan.setDisbursementStatusMessage("Status check failed: " + response.getResponseDescription());
    }

    private void handleStatusCheckException(Loan loan, Exception ex) {
        log.error("Loan disbursement status check failed for loan: {} with exception", loan.getId(), ex);

        // Keep the status as PENDING, we'll try again next time
        // Just update the message
        loan.setDisbursementStatusMessage("Status check exception: " + ex.getMessage());
    }
}
