package zw.co.innbucks.loans.core.staff;

import lombok.Getter;

import java.util.List;

/** Every row of an uploaded staff file was refused, so there is nothing to submit; the rows say why (FR-SGL-003). */
@Getter
public class StaffUploadRejectedException extends RuntimeException {

    private final transient List<StaffRegisterRowResponse> rows;

    public StaffUploadRejectedException(String fileName, List<StaffRegisterRowResponse> rows) {
        super(String.format("No row of %s can be loaded; all %d were refused", fileName, rows.size()));
        this.rows = List.copyOf(rows);
    }
}
