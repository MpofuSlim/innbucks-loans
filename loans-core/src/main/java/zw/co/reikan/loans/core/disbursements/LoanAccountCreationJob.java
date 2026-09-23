package zw.co.reikan.loans.core.disbursements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
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
        if (isLoanAccountAlreadyCreated(loan)) {
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

    private void handleSuccessfulAccountCreation(Loan loan, LoanAccountCreationResponse response) {
        log.info("Loan account created successfully for loan: {}", loan.getId());

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

    private void handleFailedAccountCreation(Loan loan, LoanAccountCreationResponse response) {
        log.info("Loan account creation failed for loan: {}", loan.getId());

        loan.setLoanAccountStatus(LoanAccountStatus.FAILED);
        loan.setDisbursementStatus(LoanDisbursementStatus.FAILED);
        loan.setDisbursementReference(response.getReference());
        loan.setDisbursementStatusMessage(truncate("InnBucks loan application failed: "
                + (response.getMessage() == null ? "unsuccessful response" : response.getMessage())));
    }

    private void handleAccountCreationException(Loan loan, Exception ex) {
        log.error("Loan account creation failed for loan: {} with exception", loan.getId(), ex);

        loan.setLoanAccountStatus(LoanAccountStatus.FAILED);
        loan.setDisbursementStatus(LoanDisbursementStatus.FAILED);
        loan.setDisbursementStatusMessage(truncate("InnBucks loan application failed: " + describe(ex)));
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
