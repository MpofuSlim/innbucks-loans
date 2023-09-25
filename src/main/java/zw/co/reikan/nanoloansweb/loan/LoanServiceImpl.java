package zw.co.reikan.nanoloansweb.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.LoanResponse;
import zw.co.reikan.nanoloansweb.Utils;
import zw.co.reikan.nanoloansweb.disbursements.LoanDisbursementStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static java.math.BigDecimal.ONE;

@Slf4j
@RequiredArgsConstructor
@Service
public class LoanServiceImpl {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private final LoanRepository loanRepository;

    public LoanResponse requestLoan(LoanRequest loanRequest) {

        log.info("Requesting loan approval: {}", loanRequest);

        final String formattedEcNumber = Utils.trimSpecialCharacters(loanRequest.getEcnumber());

        boolean hasPendingLoan = findPendingLoan(formattedEcNumber).isPresent();

        if (hasPendingLoan) {
            return LoanResponse.builder()
                    .loanApprovalStatus(LoanApprovalStatus.REJECTED)
                    .message("You have a pending loan application.")
                    .build();
        }

        final LoanDetails loanDetails = calculate(loanRequest);

        final Loan loan = Loan.builder()
                .amount(loanDetails.getPrincipal())
                .disbursementStatus(LoanDisbursementStatus.PENDING)
                .loanApprovalStatus(LoanApprovalStatus.NEW)
                .ecNumber(formattedEcNumber)
                .mobileNumber(loanRequest.getMobileNumber())
                .signature(loanRequest.getSignatureData())
                .feeRate(loanRequest.getAdminFeeRate())
                .interestRate(loanRequest.getInterestRate())
                .build();

        loanRepository.save(loan);

        return LoanResponse.builder()
                .loanApprovalStatus(LoanApprovalStatus.NEW)
                .internalReference(loan.getReference())
                .message("Loan Sent For Approval")
                .build();
    }

    public Optional<Loan> findPendingLoan(String ecNumber) {
        return loanRepository.findByEcNumberAndLoanApprovaStatus(Utils.trimSpecialCharacters(ecNumber), LoanApprovalStatus.NEW);
    }


    public LoanDetails calculate(LoanRequest request) {

        BigDecimal principalLoanAmount = getPrincipalLoanAmount(request);

        BigDecimal adminFee = principalLoanAmount
                .multiply(request.getAdminFeeRate())
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal interestRate = request.getInterestRate().divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal powerValue = interestRate.add(ONE).pow(request.getTenor());

        BigDecimal installment = principalLoanAmount.multiply(interestRate).multiply(powerValue)
                .divide(powerValue.subtract(ONE), 2, RoundingMode.HALF_UP);

        final BigDecimal disbursementAmount = principalLoanAmount.subtract(adminFee);

        return LoanDetails.builder()
                .principal(principalLoanAmount)
                .tenor(request.getTenor())
                .interestRate(request.getInterestRate())
                .disbursedAmount(disbursementAmount)
                .adminFee(adminFee)
                .installment(installment)
                .build();
    }

    private BigDecimal getPrincipalLoanAmount(LoanRequest request) {
        if (LoanAmountType.NET_OF_FEES == request.getType()) {
            return request.getAmount().divide(ONE.subtract(request.getAdminFeeRate()
                    .divide(ONE_HUNDRED)), 0, RoundingMode.CEILING);
        }
        return request.getAmount();
    }

}
