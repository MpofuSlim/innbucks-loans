package zw.co.reikan.loans.core.disbursements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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
        loan.setDisbursementStatus(LoanDisbursementStatus.SUCCESS);
        loan.setDisbursementReference(response.getReference());
        loan.setDateDisbursed(LocalDateTime.now());
        loan.setDisbursementMerchantAccountNumber(loan.getMerchant().getAccountNumber());

        notifyCustomer(loan);
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
    }

    private void handleAccountCreationException(Loan loan, Exception ex) {
        log.error("Loan account creation failed for loan: {} with exception", loan.getId(), ex);

        loan.setLoanAccountStatus(LoanAccountStatus.FAILED);
        loan.setDisbursementStatus(LoanDisbursementStatus.FAILED);
    }
}