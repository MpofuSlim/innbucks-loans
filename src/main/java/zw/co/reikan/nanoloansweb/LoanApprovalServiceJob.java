package zw.co.reikan.nanoloansweb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.loan.Loan;
import zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus;
import zw.co.reikan.nanoloansweb.loan.LoanRepository;
import zw.co.reikan.nanoloansweb.ndasenda.LoanApprovalRequest;
import zw.co.reikan.nanoloansweb.ndasenda.LoanApprovalResponse;
import zw.co.reikan.nanoloansweb.ndasenda.LoanApprovalService;
import zw.co.reikan.nanoloansweb.notifications.NotificationService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus.APPROVED;
import static zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus.PROCESSING;
import static zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus.REJECTED;

@Service
@Slf4j
@RequiredArgsConstructor
public class LoanApprovalServiceJob {

    private final LoanApprovalService loanApprovalService;
    private final LoanRepository loanRepository;
    private final NotificationService notificationService;
    Map<LoanApprovalStatus, String> smsMessages = Map.of(APPROVED, "CONGRATULATIONS! Your loan has been approved. Funds will be disbursed within 2 hours. Ref: %s.",
            REJECTED, "Loan application rejected. We understand your disappointment. Feel free to contact us for further information. Ref: %s",
            PROCESSING, "Loan application received. Your request is being processed. We'll update you soon. Ref: %s"
    );

    @Scheduled(fixedRate = 60000) // Run every 1 minute (60,000 milliseconds)
    public void processSsbApprovals() {
        log.info("SSB LoanRequests");
        final List<Loan> pendingLoans = loanRepository.findByLoanApprovalStatus(LoanApprovalStatus.NEW);
        pendingLoans.forEach(this::processLoanApproval);
    }

    private void processLoanApproval(Loan loan) {

        final LoanApprovalResponse loanApprovalResponse = loanApprovalService.requestApproval(LoanApprovalRequest.builder()
                .monthlyInstallment(loan.getGrossedMonthlyDeduction())
                .ecnumber(loan.getEcNumber())
                .idNumber(loan.getNationalIdNumber())
                .reference(loan.getReference())
                .tenor(loan.getTenor())
                .build());

        log.info("Updating loan status: {}", loanApprovalResponse);
        loan.setLoanApprovalStatus(loanApprovalResponse.getStatus());
        loan.setApprovalReference(loanApprovalResponse.getReference());
        loan.setBatchNumber(loanApprovalResponse.getBatchNumber());
        loan.setDateApproved(LocalDateTime.now());
        loan.setRepaymentStartDate(loanApprovalResponse.getStartDate());
        loan.setRepaymentEndDate(loanApprovalResponse.getEndDate());
        loanRepository.save(loan);

        log.info("Dispatching loan approved sms notification: {}", loanApprovalResponse);
        final String text = String.format(smsMessages.get(loan.getLoanApprovalStatus()), String.format("%09d", loan.getId()));
        notificationService.sendSms(loan.getMobileNumber(), text);
    }


}
