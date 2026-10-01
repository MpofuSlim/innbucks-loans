package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One line of a reconciliation's variance report (FR-SGL-008). Which fields it carries depends on its kind.
 *
 * @param fullName    the register's name for someone on it, otherwise the payroll's
 * @param rowNumbers  the payroll rows it concerns (the header is row 1); several for DUPLICATE_ON_PAYROLL, absent for
 *                    NOT_ON_PAYROLL
 * @param eligible    on LEFT_ON_PAYROLL and NOT_ON_PAYROLL only: whether they could borrow when it ran
 * @param register    on NOT_ON_PAYROLL only: their record on the register
 * @param payroll     on NOT_ON_REGISTER and UNREADABLE only: the payroll row as sent
 * @param differences on LEFT_ON_PAYROLL and DIFFERENT only: each field that disagrees, the employment status among
 *                    them for LEFT_ON_PAYROLL
 * @param errors      on UNREADABLE only: why the employee number could not be used
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffRegisterVarianceResponse(
        Long id,
        StaffRegisterVarianceKind kind,
        String employeeNumber,
        String fullName,
        List<Integer> rowNumbers,
        Boolean eligible,
        Map<String, String> register,
        Map<String, String> payroll,
        List<Difference> differences,
        Map<String, String> errors) {

    /**
     * One field the register and the payroll disagree on.
     *
     * @param payroll the payroll's value, normalised as the register would hold it; as sent when it breaks the
     *                register's rules, and absent when the cell is blank or the file has no column for the field
     * @param note    why the payroll's value could not be compared as it stands, when it could not
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Difference(String field, String register, String payroll, String note) {
    }

    static StaffRegisterVarianceResponse of(StaffRegisterVariance variance, StaffRegisterVariance.Details details) {
        return new StaffRegisterVarianceResponse(variance.getId(), variance.getKind(), variance.getEmployeeNumber(),
                variance.getFullName(), details.rowNumbers(), variance.getEligible(), details.register(),
                details.payroll(), details.differences(), details.errors());
    }
}
