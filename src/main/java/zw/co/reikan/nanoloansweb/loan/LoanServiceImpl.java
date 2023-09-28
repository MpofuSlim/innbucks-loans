package zw.co.reikan.nanoloansweb.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.nanoloansweb.LoanResponse;
import zw.co.reikan.nanoloansweb.Utils;
import zw.co.reikan.nanoloansweb.disbursements.LoanDisbursementStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
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
                .grossedMonthlyDeduction(loanDetails.getGrossedMonthlyInstallment())
                .commissionRate(loanRequest.getCommissionRate())
                .build();

        loanRepository.save(loan);

        return LoanResponse.builder()
                .loanApprovalStatus(LoanApprovalStatus.NEW)
                .internalReference(loan.getReference())
                .message("Loan Sent For Approval")
                .build();
    }

    public Optional<Loan> findPendingLoan(String ecNumber) {
        return loanRepository.findByEcNumberAndLoanApprovalStatus(Utils.trimSpecialCharacters(ecNumber),
                LoanApprovalStatus.NEW);
    }


    public LoanDetails calculate(LoanRequest request) {

        List<AmortizationEntry> schedule = new ArrayList<>();

        BigDecimal principalLoanAmount = getPrincipalLoanAmount(request);

        BigDecimal adminFee = principalLoanAmount
                .multiply(request.getAdminFeeRate())
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal interestRate = request.getInterestRate().divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal powerValue = interestRate.add(ONE).pow(request.getTenor());

        BigDecimal installment = principalLoanAmount.multiply(interestRate).multiply(powerValue)
                .divide(powerValue.subtract(ONE), 2, RoundingMode.HALF_UP);

        BigDecimal disbursementAmount = principalLoanAmount.subtract(adminFee);

        BigDecimal commissionRate = request.getCommissionRate().divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal grossedMonthlyPayment = installment.divide(ONE.subtract(commissionRate), 2, RoundingMode.HALF_UP);

        final LoanDetails loanDetails = LoanDetails.builder()
                .principal(principalLoanAmount)
                .tenor(request.getTenor())
                .interestRate(request.getInterestRate())
                .disbursedAmount(disbursementAmount)
                .amortization(schedule)
                .adminFee(adminFee)
                .commissionRate(request.getCommissionRate())
                .regularMonthlyInstallment(installment)
                .grossedMonthlyInstallment(grossedMonthlyPayment)
                .build();

        amortizeLoan(loanDetails);

        return loanDetails;

    }

    private void amortizeLoan(LoanDetails loanDetails) {
        BigDecimal interestRate = loanDetails.getInterestRate().divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal remainingPrincipal = loanDetails.getPrincipal();

        for (int paymentNumber = 1; paymentNumber <= loanDetails.getTenor(); paymentNumber++) {
            BigDecimal interestPayment = remainingPrincipal.multiply(interestRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal principalPayment = loanDetails.getRegularMonthlyInstallment().subtract(interestPayment).setScale(2, RoundingMode.HALF_UP);
            remainingPrincipal = remainingPrincipal.subtract(principalPayment).setScale(2, RoundingMode.HALF_UP);

            if (remainingPrincipal.compareTo(ONE) < 0) {
                remainingPrincipal = BigDecimal.ZERO;
            }

            AmortizationEntry entry = AmortizationEntry.builder()
                    .interestPayment(interestPayment)
                    .paymentNumber(paymentNumber)
                    .regularMonthlyPayment(loanDetails.getRegularMonthlyInstallment())
                    .grossedMonthlyPayment(loanDetails.getGrossedMonthlyInstallment())
                    .remainingPrincipal(remainingPrincipal)
                    .principalPayment(principalPayment)
                    .build();

            loanDetails.add(entry);
        }
    }

    private BigDecimal getPrincipalLoanAmount(LoanRequest request) {
        if (LoanAmountType.NET_OF_FEES == request.getType()) {
            return request.getAmount().divide(ONE.subtract(request.getAdminFeeRate()
                    .divide(ONE_HUNDRED)), 2, RoundingMode.HALF_UP);
        }
        return request.getAmount();
    }

}
