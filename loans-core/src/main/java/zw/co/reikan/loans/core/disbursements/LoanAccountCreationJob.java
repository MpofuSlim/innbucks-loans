package zw.co.reikan.loans.core.disbursements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanDisbursementRepository;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.time.LocalDateTime;

import static zw.co.reikan.loans.core.DisbursementService.SMS_MSG;
import static zw.co.reikan.loans.core.loan.LoanApprovalStatus.APPROVED;

@Service
@Slf4j
@RequiredArgsConstructor
@Profile("scheduled-tasks")
public class LoanAccountCreationJob {

    private final DisbursementService disbursementService;
    private final LoanRepository loanRepository;
    private final NotificationService notificationService;
    private final LoanDisbursementRepository loanDisbursementRepository;

    /**
     * Processes pending loan accounts that have been approved.
     * Runs every 2 minutes.
     */
    @Scheduled(fixedRate = 120_000)
    public void processLoanAccountCreation() {
        log.info("Starting LoanAccountCreationJob...");

        loanRepository.findByLoanApprovalStatusAndInternalApprovalStatusAndLoanAccountStatus(
                APPROVED,
                InternalApprovalStatus.APPROVED,
                LoanAccountStatus.PENDING
        ).forEach(this::createLoanAccount);
    }

    private void createLoanAccount(Loan loan) {
        if (isLoanAccountAlreadyCreated(loan) || mustNotBook(loan)) {
            return;
        }
        try {
            LoanAccountCreationResponse response = disbursementService.createLoanAccount(loan);

            if (response.isSuccess()) {
                handleSuccessfulAccountCreation(loan, response);
            } else {
                handleFailedAccountCreation(loan, response);
            }
        } catch (Exception ex) {
            handleAccountCreationException(loan, ex);
        }

        loanRepository.save(loan);
    }

    private boolean isLoanAccountAlreadyCreated(Loan loan) {
        if (loan.getLoanAccountStatus() == LoanAccountStatus.CREATED) {
            log.info("Loan account already created: {}", loan.getId());
            return true;
        }
        return false;
    }

    /**
     * InnBucks pays on the booking, so booking a loan that is already paid, or whose payout
     * is in flight, pays it again. Defence in depth: this job only picks account-PENDING
     * loans, but other code (an SSB re-approval) can put a loan back to PENDING.
     */
    private boolean mustNotBook(Loan loan) {
        if (loan.getDisbursementStatus() == LoanDisbursementStatus.SUCCESS) {
            log.warn("Loan {} is already disbursed; not booking it again", loan.getId());
            return true;
        }
        if (loan.getBookingFailureKind() == BookingFailureKind.AMBIGUOUS) {
            log.warn("Loan {}: an earlier InnBucks booking has an unknown outcome and may have paid it;"
                    + " held for an operator, not re-booked", loan.getId());
            return true;
        }
        if (loanDisbursementRepository.existsByLoanId(loan.getId())) {
            log.warn("Loan {} has a manual payout attempt; not booking it", loan.getId());
            return true;
        }
        return false;
    }

    private void handleSuccessfulAccountCreation(Loan loan, LoanAccountCreationResponse response) {
        log.info("Loan account created successfully for loan: {}", loan.getId());

        loan.setBookingFailureKind(null);
        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        loan.setDisbursementStatus(LoanDisbursementStatus.PENDING);
        loan.setDisbursementReference(response.getReference());
        // Don't set date disbursed yet as the disbursement is pending
        loan.setDisbursementMerchantAccountNumber(loan.getMerchant().getAccountNumber());

        // Don't notify customer yet as the disbursement is pending
    }

    private void notifyCustomer(Loan loan) {
        try {
            final String message = String.format(
                    SMS_MSG,
                    loan.getDisbursedAmount(),
                    loan.getReference(),
                    loan.getMobileNumber()
            );
            notificationService.sendSms(loan.getMobileNumber(), message);
            log.info("Notification sent successfully to customer: {}", loan.getMobileNumber());
        } catch (Exception ex) {
            log.error("Failed to send notification to customer: {}, but loan account creation was successful",
                    loan.getMobileNumber(), ex);
            // Notification failure shouldn't affect the loan account creation status
        }
    }

    /** A non-2xx InnBucks answered and we read: a definite refusal. */
    private void handleFailedAccountCreation(Loan loan, LoanAccountCreationResponse response) {
        log.info("Loan account creation failed for loan: {}", loan.getId());

        loan.setBookingFailureKind(BookingFailureKind.REFUSED);
        loan.setLoanAccountStatus(LoanAccountStatus.FAILED);
        loan.setDisbursementStatus(LoanDisbursementStatus.FAILED);
        loan.setDisbursementReference(response.getReference());
        loan.setDisbursementStatusMessage(truncate("InnBucks loan application failed: "
                + (response.getMessage() == null ? "unsuccessful response" : response.getMessage())));
    }

    private void handleAccountCreationException(Loan loan, Exception ex) {
        BookingFailureKind kind = classify(ex);
        loan.setBookingFailureKind(kind);

        if (kind == BookingFailureKind.REFUSED) {
            log.error("Loan account creation refused for loan: {}", loan.getId(), ex);
            loan.setLoanAccountStatus(LoanAccountStatus.FAILED);
            loan.setDisbursementStatus(LoanDisbursementStatus.FAILED);
            loan.setDisbursementStatusMessage(truncate("InnBucks loan application failed: " + describe(ex)));
            return;
        }

        // The booking may have landed, and InnBucks pays on booking. Marking it FAILED would
        // read as "definitively not paid" and invite a recovery payout. Treat it as booked
        // instead: the inquiry job resolves a booking that landed, and one that did not
        // simply stays PENDING for an operator — never re-booked, never re-paid.
        log.error("Loan account creation outcome unknown for loan: {}; holding it for the inquiry job",
                loan.getId(), ex);
        loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
        loan.setDisbursementStatus(LoanDisbursementStatus.PENDING);
        loan.setDisbursementReference(loan.getReference());
        loan.setDisbursementStatusMessage(truncate("InnBucks loan application outcome unknown (held, not failed): "
                + describe(ex)));
    }

    /**
     * Only InnBucks' own 4xx answer proves it did not book. A 5xx, a timeout, a reset after
     * the request left, a parse error — anything that is not a 4xx — may follow a booking
     * that landed. So may a 409 (plausibly "this participantReference already exists") and
     * a 408 (it stopped reading, which is not a statement of what it did). A 401 reaching
     * here is the answer to our one re-authenticated replay, so it counts as a refusal.
     */
    private static BookingFailureKind classify(Exception ex) {
        if (ex instanceof HttpClientErrorException http) {
            int status = http.getStatusCode().value();
            return status == 408 || status == 409 ? BookingFailureKind.AMBIGUOUS : BookingFailureKind.REFUSED;
        }
        return BookingFailureKind.AMBIGUOUS;
    }

    /**
     * The reason an operator (and the portal) can act on. For an HTTP refusal
     * that is InnBucks' own status + body — its error text is the most useful
     * thing we hold; for anything else, the exception's message. Before this,
     * a failed loan read only {@code disbursementStatus: FAILED}, with nothing
     * to say whether to fix the data, retry, or call InnBucks.
     */
    private static String describe(Exception ex) {
        if (ex instanceof RestClientResponseException http) {
            String body = http.getResponseBodyAsString();
            return "HTTP " + http.getStatusCode().value()
                    + (body == null || body.isBlank() ? "" : " " + body.strip());
        }
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /** disbursement_status_message is a VARCHAR(255). */
    private static String truncate(String message) {
        return message.length() <= 255 ? message : message.substring(0, 252) + "...";
    }
}
