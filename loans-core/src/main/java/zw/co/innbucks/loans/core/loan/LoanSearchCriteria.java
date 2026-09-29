package zw.co.innbucks.loans.core.loan;

import lombok.Builder;
import zw.co.innbucks.loans.core.disbursements.LoanDisbursementStatus;

import java.time.LocalDate;

/**
 * Filters for a loan search; every one is optional and an absent one does not filter. The dates
 * are the market's calendar days the loan was created on, both inclusive.
 */
@Builder
public record LoanSearchCriteria(
        LocalDate fromDate,
        LocalDate toDate,
        LoanApprovalStatus ssbApprovalStatus,
        InternalApprovalStatus creditApprovalStatus,
        LoanDisbursementStatus disbursementStatus,
        String merchantCode) {

    /** Every loan waiting on Credit: SSB has accepted the deduction and Credit has not decided. */
    public static LoanSearchCriteria awaitingCreditDecision() {
        return LoanSearchCriteria.builder()
                .ssbApprovalStatus(LoanApprovalStatus.APPROVED)
                .creditApprovalStatus(InternalApprovalStatus.PENDING)
                .build();
    }
}
