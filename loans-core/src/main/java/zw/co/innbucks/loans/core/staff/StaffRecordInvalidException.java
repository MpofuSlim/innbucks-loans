package zw.co.innbucks.loans.core.staff;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A staff record changed on the admin screen fails the register's rules; every failing field is named. */
@Getter
public class StaffRecordInvalidException extends RuntimeException {

    private final transient Map<String, String> fields;

    public StaffRecordInvalidException(Map<String, String> fields) {
        super("Staff record refused: " + fields.keySet());
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
