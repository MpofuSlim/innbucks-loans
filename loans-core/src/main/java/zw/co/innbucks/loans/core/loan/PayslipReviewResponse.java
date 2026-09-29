package zw.co.innbucks.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** An application in the payslip review queue: who applied, through whom, and every reason it is held. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayslipReviewResponse(
        Long loanId,
        String reference,
        LocalDateTime createdAt,
        String createdBy,
        String merchantCode,
        String firstName,
        String lastName,
        String ecNumber,
        String nationalIdNumber,
        BigDecimal grossSalary,
        BigDecimal netSalary,
        List<PayslipFraudFlagResponse> flags) {

    public static PayslipReviewResponse of(Loan loan, List<PayslipFraudFlagResponse> flags) {
        EmploymentDetail employment = loan.getEmploymentDetail();
        return new PayslipReviewResponse(loan.getId(), loan.getReference(), loan.getCreatedDate(),
                loan.getCreatedBy(), loan.getMerchant() == null ? null : loan.getMerchant().getMerchantCode(),
                loan.getFirstName(), loan.getLastName(), loan.getEcNumber(), loan.getNationalIdNumber(),
                employment == null ? null : employment.getGrossSalary(),
                employment == null ? null : employment.getNetSalary(), flags);
    }
}
