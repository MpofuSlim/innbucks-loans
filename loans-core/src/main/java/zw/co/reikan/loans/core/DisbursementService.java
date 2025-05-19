package zw.co.reikan.loans.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zw.co.reikan.loans.core.disbursements.LoanAccountCreationResponse;
import zw.co.reikan.loans.core.loan.*;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.time.LocalDateTime;

@Slf4j
@RequiredArgsConstructor
public abstract class DisbursementService {

    public static final String SMS_MSG = "Your loan of $%s with ref # %s has been disbursed to your account %s. Welcome to the Innbucks family";
    public static final String SMS_MSG_CONSUMER_FINANCE = "Your loan of $%s with ref # %s been paid to %s, you can proceed to collect goods. Thank you for Banking with Innbucks";

    private final LoanRepository loanRepository;
    private final NotificationService notificationService;
    private final LoanDisbursementRepository loanDisbursementRepository;

    public abstract DisbursementResponse disburseFunds(DisbursementRequest request);

    public abstract LoanAccountCreationResponse createLoanAccount(Loan loan);

    public void processDisbursement(DisbursementRequest request, Loan loan) {
        DisbursementResponse response = disburseFunds(request);
        log.info("Disbursement response: {}", response);

        if (DisbursementStatus.SUCCESS == response.getStatus()) {
            handleSuccessfulDisbursement(request, loan, response);
        } else {
            handleFailedDisbursement(loan, response);
        }
    }

    private void handleSuccessfulDisbursement(DisbursementRequest request, Loan loan, DisbursementResponse response) {
        // Update loan with successful disbursement details
        loan.setDisbursementStatus(response.getStatus().getLoanDisbursementStatus());
        loan.setDisbursementReference(response.getApprovalCode());
        loan.setDateDisbursed(LocalDateTime.now());
        loan.setDisbursementMerchantAccountNumber(request.getAccountNumber());
        loanRepository.save(loan);

        log.info("Sending disbursement SMS notification for loan: {}", loan.getReference());
        sendDisbursementNotification(loan);
    }

    private void handleFailedDisbursement(Loan loan, DisbursementResponse response) {
        // Increment disbursement attempts count
        int attempts = loan.getDisbursementAttempts() == null ? 1 : loan.getDisbursementAttempts() + 1;
        loan.setDisbursementAttempts(attempts);

        if (attempts < 3) {
            // Schedule next attempt with exponential backoff
            int hoursToDelay = (int) Math.pow(2.0, attempts);
            LocalDateTime nextAttemptDate = LocalDateTime.now().plusHours(hoursToDelay);
            loan.setNextDisbursementAttemptDate(nextAttemptDate);
        } else {
            // Mark as permanently failed after 3 attempts
            loan.setDisbursementStatus(response.getStatus().getLoanDisbursementStatus());
            loan.setDateDisbursed(LocalDateTime.now());
        }

        // Record disbursement attempt
        recordDisbursementAttempt(loan, response);

        // Save updated loan
        loanRepository.save(loan);
    }

    private void recordDisbursementAttempt(Loan loan, DisbursementResponse response) {
        LoanDisbursement disbursement = new LoanDisbursement();
        disbursement.setLoan(loan);
        disbursement.setDisbursementStatus(response.getStatus().getLoanDisbursementStatus());
        disbursement.setDisbursementStatusMessage(response.getMessage());
        disbursement.setDisbursementReference(response.getApprovalCode());
        loanDisbursementRepository.save(disbursement);
    }

    private void sendDisbursementNotification(Loan loan) {
        String message;

        if (loan.getMerchant().getDisbursementType() == DisbursementType.CUSTOMER_MOBILE_WALLET) {
            message = String.format(SMS_MSG,
                    loan.getDisbursedAmount(),
                    loan.getReference(),
                    loan.getMobileNumber());
        } else {
            message = String.format(SMS_MSG_CONSUMER_FINANCE,
                    loan.getDisbursedAmount(),
                    loan.getReference(),
                    loan.getMerchant().getCompanyName());
        }
        log.info("Sending disbursement notification: {} -> {}", loan.getMerchant().getDisbursementType(), message);
        notificationService.sendSms(loan.getMobileNumber(), message);
    }
}
