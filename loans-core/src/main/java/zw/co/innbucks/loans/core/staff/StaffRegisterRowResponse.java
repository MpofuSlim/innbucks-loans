package zw.co.innbucks.loans.core.staff;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One row of a batch.
 *
 * @param values  the row's fields by API name: as normalised for a staged or applied row, as sent for a refused one
 * @param errors  why a REJECTED or SKIPPED row was refused, field to message
 * @param changes on a STAGED row only: what approving it would change in the register now, field by field; every field
 *                for a new staff member, and empty when the record already holds these values
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StaffRegisterRowResponse(
        int rowNumber,
        StaffRegisterRowOutcome outcome,
        StaffRegisterRowAction action,
        Map<String, String> values,
        Map<String, String> errors,
        List<FieldChange> changes) {

    /** One field changing: {@code from} is absent for a new staff member. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldChange(String field, String from, String to) {
    }
}
