package zw.co.innbucks.loans.core.staff;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A staff record's values (FR-SGL-001), validated and normalised: what a register row applies and what a staff member
 * holds.
 *
 * @param msisdn the mobile number in international form, 2637XXXXXXXX
 */
public record StaffRecord(
        String employeeNumber,
        String fullName,
        String nationalId,
        String msisdn,
        String grade,
        String department,
        StaffEmploymentStatus employmentStatus,
        LocalDate engagementDate,
        String walletAccountNumber) {

    /** Every field by its API name, in a fixed order, as text: what the change history and diffs compare. */
    public Map<String, String> asText() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(StaffFields.EMPLOYEE_NUMBER, employeeNumber);
        fields.put(StaffFields.FULL_NAME, fullName);
        fields.put(StaffFields.NATIONAL_ID, nationalId);
        fields.put(StaffFields.MOBILE_NUMBER, msisdn);
        fields.put(StaffFields.GRADE, grade);
        fields.put(StaffFields.DEPARTMENT, department);
        fields.put(StaffFields.EMPLOYMENT_STATUS, employmentStatus == null ? null : employmentStatus.name());
        fields.put(StaffFields.ENGAGEMENT_DATE, engagementDate == null ? null : engagementDate.toString());
        fields.put(StaffFields.WALLET_ACCOUNT_NUMBER, walletAccountNumber);
        return fields;
    }
}
