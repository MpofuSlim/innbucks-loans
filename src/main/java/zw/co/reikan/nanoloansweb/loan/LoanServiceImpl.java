package zw.co.reikan.nanoloansweb.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.LoanResponse;
import zw.co.reikan.nanoloansweb.disbursements.LoanDisbursementStatus;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Service
public class LoanServiceImpl {

    private final LoanRepository loanRepository;

    public LoanResponse requestLoan(LoanRequest loanRequest) {

        log.info("Requesting loan approval: {}", loanRequest);

        boolean hasPendingLoan = findPendingLoan(loanRequest.getEcnumber()).isPresent();

        if (hasPendingLoan) {
            return LoanResponse.builder()
                    .loanStatus(LoanStatus.REJECTED)
                    .message("You have a pending loan application.")
                    .build();
        }

        final Loan loan = Loan.builder()
                .amount(loanRequest.getAmount())
                .disbursementStatus(LoanDisbursementStatus.PENDING)
                .loanStatus(LoanStatus.NEW)
                .ecNumber(loanRequest.getEcnumber())
                .mobileNumber(loanRequest.getMobileNumber())
                .signature(loanRequest.getSignatureData())
                .build();


        loanRepository.save(loan);

        return LoanResponse.builder()
                .loanStatus(LoanStatus.NEW)
                .message("Loan Sent For Approval")
                .build();
    }

    public Optional<Loan> findPendingLoan(String ecNumber) {
        return loanRepository.findByEcNumberAndLoanStatus(ecNumber, LoanStatus.NEW);
    }

}
