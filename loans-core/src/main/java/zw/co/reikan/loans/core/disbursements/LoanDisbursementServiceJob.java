package zw.co.reikan.loans.core.disbursements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.DisbursementRequest;
import zw.co.reikan.loans.core.DisbursementResponse;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.DisbursementStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.time.LocalDateTime;

import static zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.PENDING;
import static zw.co.reikan.loans.core.loan.LoanApprovalStatus.APPROVED;

@Service
@Slf4j
@RequiredArgsConstructor
@Profile("scheduled-tasks")
public class LoanDisbursementServiceJob {

    private static final String SMS_MSG = "Your mobile money account %s has been credited with $%s. Ref %s";
    private final DisbursementService disbursementService;
    private final LoanRepository loanRepository;
    private final NotificationService notificationService;

    @Scheduled(fixedRate = 120_000) // Run every 1 minute (60,000 milliseconds)
    public void processFundsDisbursements() {
        log.info("LoanDisbursementServiceJob...");
        loanRepository.findByLoanApprovalStatusAndDisbursementStatus(APPROVED, PENDING)
                .forEach(this::processLoanApproval);
    }

    private void processLoanApproval(Loan loan) {
        final DisbursementResponse response = disbursementService.disburseFunds(DisbursementRequest.builder()
                .amount(loan.getPrincipal())
                .mobileNumber(loan.getMobileNumber())
                .reference(loan.getReference())
                .build());
        log.info("Updating loan disbursement status: {}", response);
        loan.setDisbursementStatus(response.getStatus().getLoanDisbursementStatus());
        loan.setDisbursementReference(response.getApprovalCode());
        loan.setDateDisbursed(LocalDateTime.now());

        loanRepository.save(loan);

        if (DisbursementStatus.SUCCESS == response.getStatus()) {
            log.info("Dispatching loan disbursed sms notification: {}", response);
            final String message = String.format(SMS_MSG, loan.getMobileNumber(),
                    loan.getDisbursedAmount(), loan.getReference());
            notificationService.sendSms(loan.getMobileNumber(), message);
        }
    }


}
