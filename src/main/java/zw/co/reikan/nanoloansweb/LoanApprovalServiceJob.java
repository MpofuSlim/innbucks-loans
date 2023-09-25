package zw.co.reikan.nanoloansweb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.loan.Loan;
import zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus;
import zw.co.reikan.nanoloansweb.loan.LoanRepository;
import zw.co.reikan.nanoloansweb.notifications.NotificationService;
import zw.co.reikan.nanoloansweb.ndasenda.LoanApprovalRequest;
import zw.co.reikan.nanoloansweb.ndasenda.LoanApprovalResponse;
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
    Map<LoanApprovalStatus, String> smsMessages = Map.of(LoanApprovalStatus.APPROVED, "CONGRATULATIONS! Your loan has been approved. Funds will be disbursed within 2 hours. Ref: %s.",
            LoanApprovalStatus.REJECTED, "Loan application rejected. We understand your disappointment. Feel free to contact us for further information. Ref: %s"
    );

    @Scheduled(fixedRate = 60000) // Run every 1 minute (60,000 milliseconds)
    public void processSsbApprovals() {
        log.info("SSB LoanRequests");
        final List<Loan> pendingLoans = loanRepository.findByLoanApprovaStatus(LoanApprovalStatus.NEW);
        pendingLoans.forEach(this::processLoanApproval);
    }

    private void processLoanApproval(Loan loan) {
        final LoanApprovalResponse loanApprovalResponse = loanApprovalService.process(LoanApprovalRequest.builder()
                .totalAmount(loan.getAmount())
                .ecnumber(loan.getEcNumber())
                .build());
        log.info("Updating loan status: {}", loanApprovalResponse);
        loan.setLoanApprovalStatus(loanApprovalResponse.getStatus());
        loan.setApprovalReference(loanApprovalResponse.getReference());
        loan.setDateApproved(LocalDateTime.now());
        loanRepository.save(loan);

        log.info("Dispatching loan approved sms notification: {}", loanApprovalResponse);
        final String text = String.format(smsMessages.get(loan.getLoanApprovalStatus()), String.format("%09d", loan.getId()));
        notificationService.sendSms(loan.getMobileNumber(), text);
    }


}
