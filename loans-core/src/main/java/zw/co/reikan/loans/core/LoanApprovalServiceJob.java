package zw.co.reikan.loans.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanApprovalStatus;
import zw.co.reikan.loans.core.loan.LoanBatchService;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.ndasenda.LoanApprovalRequest;
import zw.co.reikan.loans.core.ndasenda.LoanApprovalResponse;
import zw.co.reikan.loans.core.ndasenda.LoanApprovalService;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
@Profile("scheduled-tasks")
public class LoanApprovalServiceJob {

    private final LoanApprovalService loanApprovalService;
    private final LoanRepository loanRepository;
    private final NotificationService notificationService;
    private final LoanBatchService loanBatchService;

    Map<LoanApprovalStatus, String> smsMessages = Map.of(LoanApprovalStatus.REJECTED, "We regret to inform you that your loan application with ref # %s has been declined. Contact Innbucks for more Info.",
            LoanApprovalStatus.PROCESSING, "Your loan application with ref # %s has been received and is being processed. You will be notified of the outcome shortly. Thank you for choosing Innbucks!"
    );

    @Scheduled(fixedRate = 60000) // Run every 1 minute (60,000 milliseconds)
    public void processSsbApprovals() {
        log.info("SSB LoanRequests");
        final List<Loan> pendingLoans = loanRepository.findByLoanApprovalStatus(LoanApprovalStatus.NEW);
        pendingLoans.forEach(this::processLoanApproval);
    }

    private void processLoanApproval(Loan loan) {
        if (loan.getApprovalAttempt() != null && loan.getApprovalAttempt() > 3) {
            log.info("Max approval attempts exceeded: loan id: {}, mobile: {}", loan.getId(), loan.getMobileNumber());
            return;
        }

        try {
            final LoanApprovalResponse loanApprovalResponse = loanApprovalService.requestApproval(LoanApprovalRequest.builder()
                    .monthlyInstallment(loan.getGrossedMonthlyDeduction())
                    .ecnumber(loan.getEcNumber())
                    .idNumber(loan.getNationalIdNumber())
                    .reference(loan.getReference())
                    .tenor(loan.getTenor())
                    .build());

            log.info(">> Updating loan status: {}", loanApprovalResponse);

            loan.setLoanApprovalStatus(loanApprovalResponse.getStatus());
            loan.setApprovalReference(loanApprovalResponse.getReference());
            loan.setBatchNumber(loanApprovalResponse.getBatchNumber());
            loan.setDateApproved(LocalDateTime.now());
            loan.setRepaymentStartDate(loanApprovalResponse.getStartDate());
            loan.setRepaymentEndDate(loanApprovalResponse.getEndDate());

            loanRepository.save(loan);

            log.info("saving loan batch: {}", loanApprovalResponse.getBatchNumber());

            loanBatchService.save(loanApprovalResponse.getBatchNumber());

            log.info("Dispatching loan approved sms notification: {}", loanApprovalResponse);
            final String text = String.format(smsMessages.get(loan.getLoanApprovalStatus()), String.format("%09d", loan.getId()));
            notificationService.sendSms(loan.getMobileNumber(), text);
        } catch (Exception ex) {
            log.error("", ex);
            loan.setApprovalAttempt(loan.getApprovalAttempt() == null ? 1 : loan.getApprovalAttempt() + 1);
            loan.setLoanApprovalStatus(LoanApprovalStatus.FAILED);
            loan.setLoanStatusMessage(StringUtils.left(ex.getMessage(), 250));
            loanRepository.save(loan);
        }
    }

}
