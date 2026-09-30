package zw.co.innbucks.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventResponse;

import zw.co.innbucks.loans.core.authority.CreditAuthorityAssessment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything a credit officer needs to decide one application, in one place (FR-SSB-015 / FR-PBL-026): the
 * application with its employer, payslip and documents; the affordability figures from its payslip; the applicant's
 * other loans; who may approve its amount; every flag the system has raised; what employment events did to it; and the
 * decisions so far. The loan's creditTurnaround says how long it has waited against the service level.
 *
 * @param creditAuthority who may approve the loan's principal, and whether the officer reading it may (FR-PBL-028)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreditWorkbenchResponse(
        LoanResponse loan,
        Affordability affordability,
        Exposure exposure,
        CreditAuthorityAssessment creditAuthority,
        List<Flag> flags,
        List<LoanEmploymentEventResponse> employmentEvents,
        List<CreditDecisionResponse> decisions) {

    /** Whether the application can be afforded, by the SSB cap and minimum take-home pay (FR-SSB-010). */
    public enum AffordabilityOutcome {
        /** The cap and the minimum take-home are not configured yet, so no pass or fail is given. */
        NOT_ASSESSED
    }

    /**
     * The payslip's figures against this loan's monthly deduction.
     *
     * @param payslipDeductions     the deductions already on the payslip, added up
     * @param monthlyDeduction      what SSB is instructed to deduct for this loan each month
     * @param netAfterDeduction     the net salary less this loan's deduction
     * @param deductionToNetPercent this loan's deduction as a share of the net salary
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Affordability(
            BigDecimal grossSalary,
            BigDecimal netSalary,
            BigDecimal payslipDeductions,
            BigDecimal monthlyDeduction,
            BigDecimal netAfterDeduction,
            BigDecimal deductionToNetPercent,
            @Schema(description = "NOT_ASSESSED until the SSB deduction cap and minimum take-home pay are configured")
            AffordabilityOutcome outcome,
            String note) {
    }

    /**
     * The applicant's other loans with InnBucks, by EC number or national ID. Open means not declined or failed,
     * and not paid out with its last deduction already past.
     *
     * @param openMonthlyDeduction what the open ones deduct each month; a paid one's deduction may already be among
     *                             the payslip's
     */
    public record Exposure(
            int openLoans,
            BigDecimal openPrincipal,
            BigDecimal openMonthlyDeduction,
            List<OtherLoan> loans) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OtherLoan(
            Long id,
            String reference,
            LoanStage stage,
            boolean open,
            BigDecimal principal,
            BigDecimal monthlyDeduction,
            Integer tenor,
            LocalDateTime createdAt,
            LocalDateTime disbursedAt,
            LocalDate repaymentEndDate) {
    }

    /**
     * Something the system raised about the application: PAYSLIP_REVIEW_PENDING, a payslip finding
     * (PAYSLIP_REUSED_BY_ANOTHER_APPLICANT, PAYSLIP_REUSED_BY_SAME_APPLICANT, DEDUCTIONS_EXCEED_GROSS_LESS_NET),
     * DOCUMENTS_AMENDED, EMPLOYMENT_EVENT_HOLD, CHECKPOINT_PENDING, DEDUCTION_CANCELLATION_REQUIRED, OTHER_OPEN_LOANS,
     * CREDIT_DECISION_OVERDUE, CREDIT_DECISION_ESCALATED, ABOVE_YOUR_APPROVAL_LIMIT or REFERRED.
     */
    public record Flag(String code, String detail) {
    }
}
