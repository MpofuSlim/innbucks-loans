package zw.co.innbucks.loans.core.loan;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * What about a new application's payslip needs a reviewer before it goes to SSB (FR-SSB-007): the same
 * payslip file already on another application, under another identity or the same one, and captured
 * figures that no real payslip can show. Reads only; the caller holds the loan and records the findings.
 *
 * <p>Checking the captured figures against the image itself would take reading the image (OCR), which
 * this service does not do.
 */
@Component
@RequiredArgsConstructor
public class PayslipFraudDetector {

    /** Earlier applications compared per payslip: enough to show a pattern, bounded for a much-reused file. */
    static final int MAX_MATCHES = 20;

    private final LoanRepository loanRepository;

    /** One reason to hold the application, and the other application it involves (if any). */
    public record Finding(PayslipFraudReason reason, Long matchedLoanId, String detail) {
    }

    /** Call before the loan is saved, so it cannot match itself. */
    public List<Finding> findingsFor(Loan loan) {
        List<Finding> findings = new ArrayList<>();
        if (loan.getPayslipSha256() != null) {
            for (PayslipMatch match : loanRepository.findPayslipMatches(loan.getPayslipSha256(),
                    PageRequest.of(0, MAX_MATCHES))) {
                String detail = "Same payslip file as loan " + String.format("%09d", match.loanId());
                findings.add(sameApplicant(loan, match)
                        ? new Finding(PayslipFraudReason.PAYSLIP_REUSED_BY_SAME_APPLICANT, match.loanId(), detail)
                        : new Finding(PayslipFraudReason.PAYSLIP_REUSED_BY_ANOTHER_APPLICANT, match.loanId(), detail));
            }
        }
        deductionsBeyondPayslip(loan).ifPresent(findings::add);
        return findings;
    }

    /** One person only when both identifiers agree: a shared EC number under another national ID is not. */
    private static boolean sameApplicant(Loan loan, PayslipMatch match) {
        return Objects.equals(identifier(loan.getEcNumber()), identifier(match.ecNumber()))
                && Objects.equals(identifier(loan.getNationalIdNumber()), identifier(match.nationalIdNumber()));
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String identifier(String value) {
        return value == null ? null : value.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    /**
     * Net pay is gross pay less every deduction, so the deductions listed can add up to less than gross
     * less net (not every line need be captured) but never to more.
     */
    private static Optional<Finding> deductionsBeyondPayslip(Loan loan) {
        EmploymentDetail employment = loan.getEmploymentDetail();
        List<PayslipDeduction> deductions = loan.getPayslipDeductions();
        if (employment == null || employment.getGrossSalary() == null || employment.getNetSalary() == null
                || deductions == null || deductions.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal captured = deductions.stream().map(PayslipDeduction::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal gap = employment.getGrossSalary().subtract(employment.getNetSalary());
        if (captured.compareTo(gap) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Finding(PayslipFraudReason.DEDUCTIONS_EXCEED_GROSS_LESS_NET, null,
                String.format("Deductions total %s but gross less net is %s", money(captured), money(gap))));
    }
}
