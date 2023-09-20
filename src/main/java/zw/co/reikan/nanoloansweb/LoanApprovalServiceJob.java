package zw.co.reikan.nanoloansweb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.notifications.NotificationService;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoanApprovalServiceJob {

    private final SsbService ssbService;
    private final LoanRepository loanRepository;
    Map<LoanStatus, String> smsMessages = Map.of(LoanStatus.APPROVED, "CONGRATULATIONS! Your loan has been approved. Funds will be disbursed within 2 hours. Thank you for choosing us.",
            LoanStatus.REJECTED, "Loan application rejected. We understand your disappointment. Feel free to contact us for further information."
    );
    private final NotificationService notificationService;

    @Scheduled(fixedRate = 60000) // Run every 1 minute (60,000 milliseconds)
    public void processSsbApprovals() {
        log.info("SSB LoanRequests");
        final List<Loan> peningLoans = loanRepository.findByLoanStatus(LoanStatus.NEW);
        peningLoans.forEach(this::processLoanApproval);
    }

    private void processLoanApproval(Loan loan) {
        final SsbResponse ssbResponse = ssbService.process(SsbApprovalRequest.builder()
                .amount(loan.getAmount())
                .ecnumber(loan.getEcNumber())
                .build());
        log.info("Updating loan status: {}", ssbResponse);
        loan.setLoanStatus(ssbResponse.getStatus().getLoanStatus());
        loanRepository.save(loan);

        log.info("Dispatching loan approved sms notification: {}", ssbResponse);
        notificationService.sendSms(loan.getMobileNumber(), smsMessages.get(loan.getLoanStatus()));
    }


}
