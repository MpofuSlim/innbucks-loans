package zw.co.reikan.loans.core.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.Utils;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.parameter.ParameterService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.math.BigDecimal.ONE;
import static zw.co.reikan.loans.core.loan.Constants.ADMI_FEE_RATE;
import static zw.co.reikan.loans.core.loan.Constants.COMMISSION_RATE;
import static zw.co.reikan.loans.core.loan.Constants.MONTHLY_INTEREST_RATE;

@Slf4j
@RequiredArgsConstructor
@Service
public class LoanServiceImpl implements LoanService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private final LoanRepository loanRepository;
    private final ParameterService parameterService;

    @Override
    public LoanResponse requestLoan(LoanRequest loanRequest) {

        log.info("Requesting loan approval: {}", loanRequest);

        final String formattedEcNumber = Utils.trimSpecialCharacters(loanRequest.getEcnumber());
        final String formattedIdNumber = Utils.trimSpecialCharacters(loanRequest.getNationalId()).toUpperCase();

        boolean hasPendingLoan = findPendingLoan(formattedEcNumber).isPresent();

        if (hasPendingLoan) {
            return LoanResponse.builder()
                    .loanApprovalStatus(LoanApprovalStatus.REJECTED)
                    .message("You have a pending loan application.")
                    .build();
        }

        final LoanDetails loanDetails = calculate(loanRequest);

        final Loan loan = Loan.builder()
                .principal(loanDetails.getPrincipal())
                .disbursementStatus(LoanDisbursementStatus.PENDING)
                .loanApprovalStatus(LoanApprovalStatus.NEW)
                .ecNumber(formattedEcNumber)
                .nationalIdNumber(formattedIdNumber)
                .mobileNumber(loanRequest.getMobileNumber())
                .signature(loanRequest.getSignatureData())
                .feeAmount(loanDetails.getAdminFeeAmount())
                .feeRate(loanDetails.getAdminFeeRate())
                .interestRate(loanDetails.getInterestRate())
                .monthlyInstallment(loanDetails.getRegularMonthlyInstallment())
                .grossedMonthlyDeduction(loanDetails.getGrossedMonthlyInstallment())
                .commissionRate(loanDetails.getCommissionRate())
                .disbursedAmount(loanDetails.getDisbursedAmount())
                .tenor(loanDetails.getTenor())
                .build();

        loanRepository.save(loan);

        return LoanResponse.builder()
                .loanApprovalStatus(LoanApprovalStatus.NEW)
                .internalReference(loan.getReference())
                .message("Loan Sent For Approval")
                .build();
    }

    public Optional<Loan> findPendingLoan(String ecNumber) {
        return loanRepository.findByEcNumberAndLoanApprovalStatus(Utils.trimSpecialCharacters(ecNumber).toUpperCase(),
                LoanApprovalStatus.NEW);
    }


    @Override
    public LoanDetails calculate(LoanRequest request) {

        List<AmortizationEntry> schedule = new ArrayList<>();

        final Map<String, String> params = parameterService.getParameterValues(
                COMMISSION_RATE,
                ADMI_FEE_RATE,
                MONTHLY_INTEREST_RATE);

        BigDecimal adminFeeRate = new BigDecimal(params.get(ADMI_FEE_RATE));
        BigDecimal monthlyInterestRate = new BigDecimal(params.get(MONTHLY_INTEREST_RATE));
        BigDecimal commissionRate = new BigDecimal(params.get(COMMISSION_RATE));

        BigDecimal principalLoanAmount = getPrincipalLoanAmount(request, adminFeeRate);

        BigDecimal adminFeeAmount = principalLoanAmount
                .multiply(adminFeeRate)
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal interestRate = monthlyInterestRate.divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal powerValue = interestRate.add(ONE).pow(request.getTenor());

        BigDecimal installment = principalLoanAmount.multiply(interestRate).multiply(powerValue)
                .divide(powerValue.subtract(ONE), 2, RoundingMode.HALF_UP);

        BigDecimal disbursementAmount = principalLoanAmount.subtract(adminFeeAmount);

        BigDecimal commissionRateToUse = commissionRate.divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal grossedMonthlyPayment = installment.divide(ONE.subtract(commissionRateToUse), 2, RoundingMode.HALF_UP);

        final LoanDetails loanDetails = LoanDetails.builder()
                .principal(principalLoanAmount)
                .tenor(request.getTenor())
                .adminFeeAmount(adminFeeAmount)
                .adminFeeRate(adminFeeRate)
                .interestRate(monthlyInterestRate)
                .disbursedAmount(disbursementAmount)
                .amortization(schedule)
                .commissionRate(commissionRate)
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

    private BigDecimal getPrincipalLoanAmount(LoanRequest request, BigDecimal adminFeeRate) {
        if (LoanAmountType.NET_OF_FEES == request.getType()) {
            return request.getAmount().divide(ONE.subtract(adminFeeRate.divide(ONE_HUNDRED)), 2, RoundingMode.HALF_UP);
        }
        return request.getAmount();
    }

    public Optional<Loan> findLatestActiveLoanByNationalId(final String nationalIdNumber) {
        log.info("Finding loan by ID Number");
        return loanRepository.findTopByNationalIdNumberAndLoanApprovalStatusIn(Utils.trimSpecialCharacters(nationalIdNumber),
                LoanApprovalStatus.activeLoanStatuses);
    }


}
