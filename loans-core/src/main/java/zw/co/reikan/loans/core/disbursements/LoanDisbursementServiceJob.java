package zw.co.reikan.loans.core.disbursements;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.DisbursementRequest;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.DisbursementType;
import zw.co.reikan.loans.core.loan.InternalApprovalStatus;
import zw.co.reikan.loans.core.loan.Loan;
import zw.co.reikan.loans.core.loan.LoanRepository;
import zw.co.reikan.loans.core.parameter.ParameterService;

import static zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.PENDING;
import static zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS;

@Service
@Slf4j
@RequiredArgsConstructor
@Profile("scheduled-tasks")
public class LoanDisbursementServiceJob {

    private final DisbursementService disbursementService;
    private final LoanRepository loanRepository;
    private final ParameterService parameterService;

    @Scheduled(fixedRate = 120_000) // Run every 1 minute (60,000 milliseconds)
    public void processFundsDisbursements() {
        log.info("LoanDisbursementServiceJob...");
        loanRepository.findByLoanAccountStatusAndDisbursementStatusAndInternalApprovalStatus(LoanAccountStatus.CREATED,
                        PENDING, InternalApprovalStatus.APPROVED)
                .forEach(this::processLoanDisbursement);
    }

    private void processLoanDisbursement(Loan loan) {
        if (loan.getDisbursementStatus() == SUCCESS) {
            log.info("Loan already disbursed");
            return;
        }

        DisbursementRequest request = DisbursementRequest.builder()
                .amount(loan.getDisbursedAmount())
                .mobileNumber(loan.getMobileNumber())
                .reference(loan.getReference())
                .disbursementType(loan.getMerchant().getDisbursementType())
                .accountNumber(getAccountNumber(loan))
                .build();

        disbursementService.processDisbursement(request, loan);
    }

    private String getAccountNumber(Loan loan) {
        if (loan.getMerchant() == null || loan.getMerchant().getDisbursementType() == DisbursementType.CUSTOMER_MOBILE_WALLET) {
            return "";
        }
        return loan.getMerchant().getAccountNumber();
        //return parameterService.getParameterValue(String.format("innbucks.merchant.account.%s", loan.getMerchant().name().toLowerCase()),
        //        String.class);
    }

}
