package zw.co.innbucks.loans.core.loan;

/** Why an application's payslip was held for review (FR-SSB-007). */
public enum PayslipFraudReason {

    /** The same payslip file is on another application under a different EC number or national ID. */
    PAYSLIP_REUSED_BY_ANOTHER_APPLICANT,

    /** The same payslip file is on an earlier application by this applicant. */
    PAYSLIP_REUSED_BY_SAME_APPLICANT,

    /**
     * The deductions captured add up to more than gross pay less net pay, which no real payslip can show:
     * a figure was typed or altered.
     */
    DEDUCTIONS_EXCEED_GROSS_LESS_NET
}
