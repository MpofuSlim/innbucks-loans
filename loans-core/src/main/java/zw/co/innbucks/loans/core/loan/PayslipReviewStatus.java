package zw.co.innbucks.loans.core.loan;

/**
 * Where an application held for payslip review stands (FR-SSB-007). No status means nothing about its
 * payslip raised a concern. PENDING holds it back from SSB; CLEARED releases it; CONFIRMED means the
 * suspicion was upheld and the application was rejected.
 */
public enum PayslipReviewStatus {
    PENDING, CLEARED, CONFIRMED
}
