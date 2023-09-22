package zw.co.reikan.nanoloansweb.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.LoanResponse;
import zw.co.reikan.nanoloansweb.Utils;
import zw.co.reikan.nanoloansweb.disbursements.LoanDisbursementStatus;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Service
public class LoanServiceImpl {

    private final LoanRepository loanRepository;

    public LoanResponse requestLoan(LoanRequest loanRequest) {

        log.info("Requesting loan approval: {}", loanRequest);

        final String formattedEcNumber = Utils.trimSpecialCharacters(loanRequest.getEcnumber());

        boolean hasPendingLoan = findPendingLoan(formattedEcNumber).isPresent();

        if (hasPendingLoan) {
            return LoanResponse.builder()
                    .loanApprovaStatus(LoanApprovaStatus.REJECTED)
                    .message("You have a pending loan application.")
                    .build();
        }


        final Loan loan = Loan.builder()
                .amount(loanRequest.getAmount())
                .disbursementStatus(LoanDisbursementStatus.PENDING)
                .loanApprovaStatus(LoanApprovaStatus.NEW)
                .ecNumber(formattedEcNumber)
                .mobileNumber(loanRequest.getMobileNumber())
                .internalReference(Utils.generateReference(loanRequest.getMobileNumber()))
                .signature(loanRequest.getSignatureData())
                .build();

        loanRepository.save(loan);

        return LoanResponse.builder()
                .loanApprovaStatus(LoanApprovaStatus.NEW)
                .internalReference(String.format("%09d", loan.getId()))
                .message("Loan Sent For Approval")
                .build();
    }

    public Optional<Loan> findPendingLoan(String ecNumber) {
        return loanRepository.findByEcNumberAndLoanApprovaStatus(Utils.trimSpecialCharacters(ecNumber), LoanApprovaStatus.NEW);
    }

}
