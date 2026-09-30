package zw.co.innbucks.loans.core.exception;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * An application submitted with fields still missing or invalid. Carries each field with why, keyed by its
 * path ({@code employmentDetail.employerName}), in the same shape a refused {@code POST /loans} body gets.
 */
public class IncompleteApplicationException extends RuntimeException {

    private final transient Map<String, String> fields;

    public IncompleteApplicationException(Map<String, String> fields) {
        super(fields.entrySet().stream()
                .map(field -> field.getKey() + ": " + field.getValue())
                .collect(Collectors.joining("; ")));
        this.fields = Map.copyOf(fields);
    }

    public Map<String, String> getFields() {
        return fields;
    }
}
