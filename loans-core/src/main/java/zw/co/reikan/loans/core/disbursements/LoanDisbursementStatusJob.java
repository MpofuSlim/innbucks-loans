package zw.co.reikan.loans.core.disbursements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
@Profile("scheduled-tasks")
public class LoanDisbursementStatusJob {

    private final DisbursementService disbursementService;
    private final LoanRepository loanRepository;
    private final NotificationService notificationService;

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
        try {
            log.debug("Checking disbursement status for loan: {}", loan.getId());
            LoanDisbursementStatusResponse response = disbursementService.checkLoanDisbursementStatus(loan);

            if (response == null) {
                log.warn("Received null response when checking disbursement status for loan: {}", loan.getId());
                loan.setDisbursementStatusMessage("Status check returned null response");
            } else if (response.isSuccess()) {
                handleSuccessfulStatusCheck(loan, response);
            } else {
                handleFailedStatusCheck(loan, response);
            }
        } catch (Exception ex) {
            handleStatusCheckException(loan, ex);
        }

        loanRepository.save(loan);
    }

    private void handleSuccessfulStatusCheck(Loan loan, LoanDisbursementStatusResponse response) {
        log.info("Loan disbursement status check successful for loan: {}", loan.getId());

        LoanDisbursementStatus newStatus = response.getStatus();
        loan.setDisbursementStatus(newStatus);

        if (newStatus == LoanDisbursementStatus.SUCCESS) {
            // Loan has been successfully disbursed
            loan.setDateDisbursed(LocalDateTime.now());
            notifyCustomer(loan);
        } else if (newStatus == LoanDisbursementStatus.FAILED) {
            // Loan disbursement has failed
            loan.setDisbursementStatusMessage(response.getResponseDescription());
        }
        // If still PENDING, do nothing special
    }

    private void notifyCustomer(Loan loan) {
        try {
            final String message = DisbursementService.walletDisbursementSms(loan);
            notificationService.sendSms(loan.getMobileNumber(), message);
            log.info("Notification sent successfully to customer: {}", loan.getMobileNumber());
        } catch (Exception ex) {
            log.error("Failed to send notification to customer: {}, but loan disbursement was successful",
                    loan.getMobileNumber(), ex);
            // Notification failure shouldn't affect the loan disbursement status
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
