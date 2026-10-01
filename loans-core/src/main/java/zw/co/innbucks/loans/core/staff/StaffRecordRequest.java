package zw.co.innbucks.loans.core.staff;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One staff record to add or change on the admin screen (FR-SGL-002), for a second person to approve. The whole record
 * is sent, keyed by employee number: a new number adds a staff member, a known one replaces their record. Checked by
 * the same rules as a row of an uploaded file ({@link StaffRecordParser}), so every field is text here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffRecordRequest {

    @Schema(example = "E1043")
    private String employeeNumber;

    @Schema(example = "Tendai Moyo")
    private String fullName;

    @ToString.Exclude
    @Schema(example = "63-2345678-B-42")
    private String nationalId;

    @ToString.Exclude
    @Schema(example = "0771234000")
    private String mobileNumber;

    @Schema(example = "C4")
    private String grade;

    @Schema(example = "Finance")
    private String department;

    @Schema(description = "ACTIVE, RESIGNED, TERMINATED, SUSPENDED or UNPAID_LEAVE", example = "ACTIVE")
    private String employmentStatus;

    @Schema(description = "yyyy-MM-dd (dd/MM/yyyy also accepted)", example = "2015-02-02")
    private String engagementDate;

    @ToString.Exclude
    @Schema(description = "The InnBucks wallet (a mobile number) or account number", example = "0771234000")
    private String walletAccountNumber;

    @Size(max = 255, message = "Comment must be at most 255 characters")
    @Schema(description = "For the checker", example = "Promoted to C4 from 1 October")
    private String comment;

    /** The record's fields by {@link StaffFields} name, as sent. */
    public Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(StaffFields.EMPLOYEE_NUMBER, employeeNumber);
        values.put(StaffFields.FULL_NAME, fullName);
        values.put(StaffFields.NATIONAL_ID, nationalId);
        values.put(StaffFields.MOBILE_NUMBER, mobileNumber);
        values.put(StaffFields.GRADE, grade);
        values.put(StaffFields.DEPARTMENT, department);
        values.put(StaffFields.EMPLOYMENT_STATUS, employmentStatus);
        values.put(StaffFields.ENGAGEMENT_DATE, engagementDate);
        values.put(StaffFields.WALLET_ACCOUNT_NUMBER, walletAccountNumber);
        return values;
    }
}
