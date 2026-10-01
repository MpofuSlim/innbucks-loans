package zw.co.innbucks.loans.core.staff;

/**
 * An approved register batch changed a staff member's employment status. Published inside the approval's transaction,
 * so whatever a listener does commits or rolls back with the change itself: a member who leaves stops being offered a
 * loan at once (FR-SGL-007), not at the next weekly run.
 */
public record StaffEmploymentStatusChanged(Long staffMemberId, String employeeNumber, StaffEmploymentStatus from,
                                           StaffEmploymentStatus to, Long batchId, String approvedBy) {
}
