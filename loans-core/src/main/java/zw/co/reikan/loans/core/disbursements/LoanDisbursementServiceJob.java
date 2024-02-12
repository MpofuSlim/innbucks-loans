package zw.co.reikan.loans.core.disbursements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.DisbursementRequest;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanRepository;

import static zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.PENDING;
import static zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS;

@Service
@Slf4j
@RequiredArgsConstructor
@Profile("scheduled-tasks")
public class LoanDisbursementServiceJob {

    private final DisbursementService disbursementService;
    private final LoanRepository loanRepository;

    @Scheduled(fixedRate = 120_000) // Run every 1 minute (60,000 milliseconds)
    public void processFundsDisbursements() {
        log.info("LoanDisbursementServiceJob...");
        loanRepository.findByLoanAccountStatusAndDisbursementStatus(LoanAccountStatus.CREATED, PENDING)
                .forEach(this::processLoanApproval);
    }

    private void processLoanApproval(Loan loan) {
        if (loan.getDisbursementStatus() == SUCCESS) {
            log.info("Loan already disbursed");
            return;
        }
        disbursementService.processDisbursement(DisbursementRequest.builder()
                .amount(loan.getDisbursedAmount())
                .mobileNumber(loan.getMobileNumber())
                .reference(loan.getReference())
                .build(), loan);
    }

}
