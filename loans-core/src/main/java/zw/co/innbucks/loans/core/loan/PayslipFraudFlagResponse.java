package zw.co.innbucks.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One reason an application is held, with the other application it involves: who applied with the same
 * payslip, when, and how that application stands (including whether it was itself confirmed as fraud).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PayslipFraudFlagResponse(
        PayslipFraudReason reason,
        String detail,
        Long matchedLoanId,
        String matchedReference,
        String matchedFirstName,
        String matchedLastName,
        String matchedEcNumber,
        String matchedNationalIdNumber,
        LocalDateTime matchedCreatedAt,
        LoanApprovalStatus matchedSsbApprovalStatus,
        InternalApprovalStatus matchedCreditApprovalStatus,
        PayslipReviewStatus matchedPayslipReviewStatus) {

    public static PayslipFraudFlagResponse of(PayslipFraudFlag flag, Loan matched) {
        if (matched == null) {
            return new PayslipFraudFlagResponse(flag.getReason(), flag.getDetail(), flag.getMatchedLoanId(),
                    null, null, null, null, null, null, null, null, null);
        }
        return new PayslipFraudFlagResponse(flag.getReason(), flag.getDetail(), matched.getId(),
                matched.getReference(), matched.getFirstName(), matched.getLastName(), matched.getEcNumber(),
                matched.getNationalIdNumber(), matched.getCreatedDate(), matched.getLoanApprovalStatus(),
                matched.getInternalApprovalStatus(), matched.getPayslipReviewStatus());
    }
}
