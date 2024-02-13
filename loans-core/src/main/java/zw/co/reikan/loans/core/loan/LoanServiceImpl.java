package zw.co.reikan.loans.core.loan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.Utils;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;
import zw.co.reikan.loans.core.parameter.ParameterService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.math.BigDecimal.ONE;
import static org.springframework.data.jpa.domain.Specification.where;
import static zw.co.reikan.loans.core.loan.Constants.*;
import static zw.co.reikan.loans.core.loan.LoanSpecification.*;

@Slf4j
@RequiredArgsConstructor
@Service
public class LoanServiceImpl implements LoanService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final String EC_NUMBER_REGEX_FORMAT = "^[0-9]{7}[a-zA-Z]$";
    private final LoanRepository loanRepository;
    private final ParameterService parameterService;
    private final LoanMapper loanMapper;

    public List<LoanDto> findLoans(FindLoansRequest findLoansRequest) {
        final List<Loan> all = loanRepository.findAll(where(withApprovalStatus(findLoansRequest.getApprovalStatus()))
                .and(withDisbursementStatus(findLoansRequest.getDisbursementStatus()))
                .and(withCreatedDateBetween(atStartOfDay(findLoansRequest.getFromDate()), atEndOfDay(findLoansRequest.getToDate()))));
        return loanMapper.fromLoans(all);
    }

    @Override
    public LoanDto getLoan(Long id) {
        return loanRepository.findById(id)
                .map(loanMapper::fromLoan)
                .orElseThrow();
    }

    private LocalDateTime atStartOfDay(LocalDate localDate) {
        if (localDate == null) {
            return null;
        }
        return localDate.atStartOfDay();
    }

    private LocalDateTime atEndOfDay(LocalDate localDate) {
        if (localDate == null) {
            return null;
        }
        return localDate.atTime(LocalTime.MAX);
    }

    @Override
    public LoanResponse requestLoan(LoanRequest loanRequest) {

            log.info("Requesting loan approval: {}", loanRequest);

            final String formattedEcNumber = Utils.trimSpecialCharacters(loanRequest.getEcnumber());

            if (!formattedEcNumber.matches(EC_NUMBER_REGEX_FORMAT)) {
                throw new IllegalArgumentException("EC Number is not valid");
            }

            val dateOfBirth = loanRequest.getDateOfBirth();
            if (dateOfBirth == null || dateOfBirth.isAfter(LocalDate.now().minusYears(18))) {
                throw new IllegalArgumentException("Must be 18+ years");
            }

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
                    .firstName(loanRequest.getFname())
                    .lastName(loanRequest.getLname())
                    .dateOfBirth(dateOfBirth)
                    .agentCommission(loanDetails.getAgentCommission())
                    .agentCommissionRate(loanDetails.getAgentCommissionRate())
                    .numberOfDependencies(loanRequest.getNumberOfDependencies())
                    .educationLevel(loanRequest.getEducationLevel())
                    .maritalStatus(loanRequest.getMaritalStatus())
                    .alternateContactNumber(loanRequest.getAlternateContactNumber())
                    .placeOfBirth(loanRequest.getPlaceOfBirth())
                    .title(loanRequest.getTitle())
                    .email(loanRequest.getEmail())
                    .educationLevel(loanRequest.getEducationLevel())
                    .address(loanRequest.getAddress())
                    .employmentDetail(loanRequest.getEmploymentDetail())
                    .nextOfKin(loanRequest.getNextOfKin())
                    .witness(loanRequest.getWitness())
                    .loanPurpose(loanRequest.getPurposeOfLoan())
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
                COMMISSION_RATE, ADMI_FEE_RATE, MONTHLY_INTEREST_RATE,
                AGENT_COMMISSION_RATE, MINIMUM_LOAN_AMOUNT, MAXIMUM_LOAN_AMOUNT, MINIMUM_LOAN_TENOR, MAXIMUM_LOAN_TENOR);


        int minLoanTenor = Integer.parseInt(String.valueOf(params.get(MINIMUM_LOAN_TENOR)));
        int maxLoanTenor = Integer.parseInt(String.valueOf(params.get(MAXIMUM_LOAN_TENOR)));

        if (request.getTenor() < minLoanTenor || request.getTenor() > maxLoanTenor) {
            throw new IllegalArgumentException(String.format("Loan tenor should be between %s and %s", minLoanTenor, maxLoanTenor));
        }

        BigDecimal adminFeeRate = new BigDecimal(params.get(ADMI_FEE_RATE));
        BigDecimal principalLoanAmount = getPrincipalLoanAmount(request, adminFeeRate);
        BigDecimal minLoanAmount = new BigDecimal(params.get(MINIMUM_LOAN_AMOUNT));

        BigDecimal maxLoanAmount = new BigDecimal(params.get(MAXIMUM_LOAN_AMOUNT));
        if (principalLoanAmount.compareTo(minLoanAmount) < 0 || principalLoanAmount.compareTo(maxLoanAmount) > 0) {
            throw new IllegalArgumentException(String.format("Loan amount should be between %s and %s", minLoanAmount, maxLoanAmount));
        }

        BigDecimal adminFeeAmount = principalLoanAmount
                .multiply(adminFeeRate)
                .divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal monthlyInterestRate = new BigDecimal(params.get(MONTHLY_INTEREST_RATE));
        BigDecimal interestRate = monthlyInterestRate.divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal powerValue = interestRate.add(ONE).pow(request.getTenor());

        BigDecimal installment = principalLoanAmount.multiply(interestRate).multiply(powerValue)
                .divide(powerValue.subtract(ONE), 2, RoundingMode.HALF_UP);

        BigDecimal disbursementAmount = principalLoanAmount.subtract(adminFeeAmount);

        BigDecimal commissionRate = new BigDecimal(params.get(COMMISSION_RATE));
        BigDecimal commissionRateToUse = commissionRate.divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP);

        BigDecimal grossedMonthlyPayment = installment.divide(ONE.subtract(commissionRateToUse), 2, RoundingMode.HALF_UP);

        BigDecimal agentCommissionRate = new BigDecimal(params.get(AGENT_COMMISSION_RATE));
        BigDecimal agentCommissionAmount = principalLoanAmount.multiply(agentCommissionRate.divide(ONE_HUNDRED, 2, RoundingMode.HALF_UP));

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
                .agentCommission(agentCommissionAmount)
                .agentCommissionRate(agentCommissionRate)
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

        if (request.getAmount() == null) {
            throw new IllegalArgumentException("Loan amount is required");
        }

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
