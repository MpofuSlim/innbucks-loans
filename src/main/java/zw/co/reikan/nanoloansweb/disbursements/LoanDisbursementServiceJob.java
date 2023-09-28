package zw.co.reikan.nanoloansweb.disbursements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.DisbursementRequest;
import zw.co.reikan.nanoloansweb.DisbursementResponse;
import zw.co.reikan.nanoloansweb.DisbursementService;
import zw.co.reikan.nanoloansweb.loan.Loan;
import zw.co.reikan.nanoloansweb.loan.LoanRepository;
import zw.co.reikan.nanoloansweb.notifications.NotificationService;

import java.time.LocalDateTime;

import static zw.co.reikan.nanoloansweb.disbursements.LoanDisbursementStatus.PENDING;
import static zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus.APPROVED;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoanDisbursementServiceJob {

    private static final String SMS_MSG = "Funds Alert: Your mobile money account %s has been credited with USD %s. For any queries, contact our support team.";
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
                .amount(loan.getAmount())
                .mobileNumber(loan.getMobileNumber())
                .build());
        log.info("Updating loan disbursement status: {}", response);
        loan.setDisbursementStatus(response.getStatus().getLoanDisbursementStatus());
        loan.setDisbursementReference(response.getApprovalCode());
        loan.setDateDisbursed(LocalDateTime.now());
        loanRepository.save(loan);

        log.info("Dispatching loan disbursed sms notification: {}", response);
        final String message = String.format(SMS_MSG, loan.getMobileNumber(), loan.getAmount());
        notificationService.sendSms(loan.getMobileNumber(), message);
    }


}
