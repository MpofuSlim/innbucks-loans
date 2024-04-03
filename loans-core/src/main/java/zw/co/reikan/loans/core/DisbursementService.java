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

    private static final String SMS_MSG = "Your mobile money account %s has been credited with $%s. Ref %s";

    private final LoanRepository loanRepository;
    private final NotificationService notificationService;
    private final LoanDisbursementRepository loanDisbursementRepository;

    public abstract DisbursementResponse disburseFunds(DisbursementRequest request);

    public abstract LoanAccountCreationResponse createLoanAccount(Loan loan);

    public void processDisbursement(DisbursementRequest request, Loan loan) {

        final DisbursementResponse response = disburseFunds(request);

        log.info("Updating loan disbursement status: {}", response);

        if (DisbursementStatus.SUCCESS == response.getStatus()) {
            loan.setDisbursementStatus(response.getStatus().getLoanDisbursementStatus());
            loan.setDisbursementReference(response.getApprovalCode());
            loan.setDateDisbursed(LocalDateTime.now());
            loan.setDisbursementMerchantAccountNumber(request.getAccountNumber());
            loanRepository.save(loan);
            log.info("Dispatching loan disbursed sms notification: {}", response);
            final String message = String.format(SMS_MSG, loan.getMobileNumber(),
                    loan.getDisbursedAmount(), loan.getReference());
            notificationService.sendSms(loan.getMobileNumber(), message);
        } else {
            loan.setDisbursementAttempts(loan.getDisbursementAttempts() == null ? 1 : loan.getDisbursementAttempts() + 1);

            if (loan.getDisbursementAttempts() < 3) {
                final LocalDateTime nextDisbursementAttemptDate = LocalDateTime.now()
                        .plusHours((int) Math.pow(2.0, loan.getDisbursementAttempts()));
                loan.setNextDisbursementAttemptDate(nextDisbursementAttemptDate);
            } else {
                loan.setDisbursementStatus(response.getStatus().getLoanDisbursementStatus());
                loan.setDateDisbursed(LocalDateTime.now());
            }
            final LoanDisbursement disbursement = new LoanDisbursement();
            disbursement.setLoan(loan);
            disbursement.setDisbursementStatus(response.getStatus().getLoanDisbursementStatus());
            disbursement.setDisbursementStatusMessage(response.getMessage());
            disbursement.setDisbursementReference(response.getApprovalCode());
            loanDisbursementRepository.save(disbursement);
            loanRepository.save(loan);

        }
    }
}
