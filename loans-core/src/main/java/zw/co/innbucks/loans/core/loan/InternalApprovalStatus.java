package zw.co.innbucks.loans.core.loan;

/**
 * Credit's decision on a loan (the API's {@code creditApprovalStatus}). RETURNED sends it back to the
 * originator for more information; resubmitting it puts it back to PENDING in the credit queue.
 */
public enum InternalApprovalStatus {
    PENDING, APPROVED, REJECTED, RETURNED
}
