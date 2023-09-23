package zw.co.reikan.nanoloansweb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.loan.Loan;
import zw.co.reikan.nanoloansweb.loan.LoanApprovaStatus;
import zw.co.reikan.nanoloansweb.loan.LoanRepository;
import zw.co.reikan.nanoloansweb.notifications.NotificationService;
import zw.co.reikan.nanoloansweb.ndasenda.LoanApprovalRequest;
import zw.co.reikan.nanoloansweb.ndasenda.SsbResponse;
import zw.co.reikan.nanoloansweb.ndasenda.LoanApprovalService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoanApprovalServiceJob {

    private final LoanApprovalService loanApprovalService;
    private final LoanRepository loanRepository;
    private final NotificationService notificationService;
    Map<LoanApprovaStatus, String> smsMessages = Map.of(LoanApprovaStatus.APPROVED, "CONGRATULATIONS! Your loan has been approved. Funds will be disbursed within 2 hours. Ref: %s.",
            LoanApprovaStatus.REJECTED, "Loan application rejected. We understand your disappointment. Feel free to contact us for further information. Ref: %s"
    );

    @Scheduled(fixedRate = 60000) // Run every 1 minute (60,000 milliseconds)
    public void processSsbApprovals() {
        log.info("SSB LoanRequests");
        final List<Loan> pendingLoans = loanRepository.findByLoanApprovaStatus(LoanApprovaStatus.NEW);
        pendingLoans.forEach(this::processLoanApproval);
    }

    private void processLoanApproval(Loan loan) {
        final SsbResponse ssbResponse = loanApprovalService.process(LoanApprovalRequest.builder()
                .totalAmount(loan.getAmount())
                .ecnumber(loan.getEcNumber())
                .build());
        log.info("Updating loan status: {}", ssbResponse);
        loan.setLoanApprovaStatus(ssbResponse.getStatus().getLoanStatus());
        loan.setApprovalReference(ssbResponse.getReference());
        loan.setDateApproved(LocalDateTime.now());
        loanRepository.save(loan);

        log.info("Dispatching loan approved sms notification: {}", ssbResponse);
        final String text = String.format(smsMessages.get(loan.getLoanApprovaStatus()),String.format("%09d", loan.getId()));
        notificationService.sendSms(loan.getMobileNumber(), text);
    }


}
