package zw.co.innbucks.loans.core.staff.loan;

import java.util.Map;

/** A borrower's request with a field the journey cannot take, keyed by field: a 400, nothing changed. */
public class StaffLoanRequestInvalidException extends RuntimeException {

    private final transient Map<String, String> fields;

    public StaffLoanRequestInvalidException(String field, String problem) {
        super(field + ": " + problem);
        this.fields = Map.of(field, problem);
    }

    public Map<String, String> getFields() {
        return fields;
    }
}
