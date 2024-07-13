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

import static zw.co.reikan.loans.core.loan.LoanApprovalStatus.APPROVED;

@Service
@Slf4j
@RequiredArgsConstructor
@Profile("scheduled-tasks")
public class LoanAccountCreationJob {

    private final DisbursementService disbursementService;
    private final LoanRepository loanRepository;

    @Scheduled(fixedRate = 120_000) // Run every 1 minute (60,000 milliseconds)
    public void processFundsDisbursements() {
        log.info("LoanAccountCreationJob...");
        loanRepository.findByLoanApprovalStatusAndLoanAccountStatus(APPROVED, LoanAccountStatus.PENDING)
                .forEach(this::createLoanAccount);
    }

    private void createLoanAccount(Loan loan) {
        if (loan.getLoanAccountStatus() == LoanAccountStatus.CREATED) {
            log.info("Loan account already created: {}", loan.getId());
            return;
        }
        try {

            LoanAccountCreationResponse loanAccount = disbursementService.createLoanAccount(loan);

            if (loanAccount.isSuccess()) {
                log.info("Loan account created successfully");
                loan.setLoanAccountStatus(LoanAccountStatus.CREATED);
                loan.setInternalApprovalStatus(InternalApprovalStatus.PENDING);
            } else {
                loan.setLoanAccountStatus(LoanAccountStatus.FAILED);
                log.info("Loan account creation failed");
            }
        } catch (Exception ex) {
            loan.setLoanAccountStatus(LoanAccountStatus.FAILED);
            log.error("Loan account creation failed: ", ex);
        }
        loanRepository.save(loan);
    }

}
