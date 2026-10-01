package zw.co.innbucks.loans.core.staff;

import java.util.List;

/** The API names of a staff record's fields, as requests, errors, diffs and the change history use them. */
public final class StaffFields {

    public static final String EMPLOYEE_NUMBER = "employeeNumber";
    public static final String FULL_NAME = "fullName";
    public static final String NATIONAL_ID = "nationalId";
    public static final String MOBILE_NUMBER = "mobileNumber";
    public static final String GRADE = "grade";
    public static final String DEPARTMENT = "department";
    public static final String EMPLOYMENT_STATUS = "employmentStatus";
    public static final String ENGAGEMENT_DATE = "engagementDate";
    public static final String WALLET_ACCOUNT_NUMBER = "walletAccountNumber";

    /** Every field, in the order a file's columns and a record's history are shown. */
    public static final List<String> ALL = List.of(EMPLOYEE_NUMBER, FULL_NAME, NATIONAL_ID, MOBILE_NUMBER, GRADE,
            DEPARTMENT, EMPLOYMENT_STATUS, ENGAGEMENT_DATE, WALLET_ACCOUNT_NUMBER);

    private StaffFields() {
    }
}
