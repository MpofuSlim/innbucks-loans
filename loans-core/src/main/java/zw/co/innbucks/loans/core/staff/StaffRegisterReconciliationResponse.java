package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * A reconciliation of the staff register with the HR payroll master, and what it found (FR-SGL-008).
 *
 * @param comparedFields       the register fields the payroll master had a column for, besides the employee number;
 *                             empty when it had only employee numbers, so only who is on which was checked
 * @param payrollRows          the payroll master's rows
 * @param registerMembers      how many staff members the register held
 * @param matched              employees on both whose compared fields all agree
 * @param different            employees on both with at least one field that disagrees, other than those below
 * @param leftOnPayroll        register members still employed whom the payroll's status says have left
 * @param leftOnPayrollEligible of those, how many could borrow when it ran: the ones to act on first
 * @param notOnRegister        employees on the payroll as still employed but not on the register
 * @param notOnPayroll         register members still employed (not RESIGNED or TERMINATED) but not on the payroll
 * @param notOnPayrollEligible of those, how many could borrow when it ran: the next ones to act on
 * @param duplicatesOnPayroll  employee numbers on more than one payroll row
 * @param unreadableRows       payroll rows with no usable employee number
 * @param variances            every variance, all kinds together; 0 means the register matches the payroll
 * @param ignoredColumns       on the response to running it only: header cells the file had that the register does
 *                             not use
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffRegisterReconciliationResponse(
        Long id,
        String fileName,
        String runBy,
        LocalDateTime runAt,
        String comment,
        List<String> comparedFields,
        int payrollRows,
        int registerMembers,
        int matched,
        int different,
        int leftOnPayroll,
        int leftOnPayrollEligible,
        int notOnRegister,
        int notOnPayroll,
        int notOnPayrollEligible,
        int duplicatesOnPayroll,
        int unreadableRows,
        int variances,
        List<String> ignoredColumns) {

    static StaffRegisterReconciliationResponse of(StaffRegisterReconciliation reconciliation,
                                                  List<String> ignoredColumns) {
        List<String> compared = reconciliation.getComparedFields().isEmpty() ? List.of()
                : Arrays.asList(reconciliation.getComparedFields().split(","));
        int variances = reconciliation.getDifferent() + reconciliation.getLeftOnPayroll()
                + reconciliation.getNotOnRegister()
                + reconciliation.getNotOnPayroll() + reconciliation.getDuplicatesOnPayroll()
                + reconciliation.getUnreadableRows();
        return new StaffRegisterReconciliationResponse(reconciliation.getId(), reconciliation.getFileName(),
                reconciliation.getRunBy(), reconciliation.getRunAt(), reconciliation.getComment(), compared,
                reconciliation.getPayrollRows(), reconciliation.getRegisterMembers(), reconciliation.getMatched(),
                reconciliation.getDifferent(), reconciliation.getLeftOnPayroll(),
                reconciliation.getLeftOnPayrollEligible(), reconciliation.getNotOnRegister(),
                reconciliation.getNotOnPayroll(),
                reconciliation.getNotOnPayrollEligible(), reconciliation.getDuplicatesOnPayroll(),
                reconciliation.getUnreadableRows(), variances, ignoredColumns);
    }
}
