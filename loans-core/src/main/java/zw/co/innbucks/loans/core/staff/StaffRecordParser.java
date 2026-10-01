package zw.co.innbucks.loans.core.staff;

import org.apache.commons.lang3.StringUtils;
import zw.co.innbucks.loans.core.MsisdnUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one set of rules a staff record must meet (FR-SGL-001, FR-SGL-003), applied alike to a row of an uploaded file
 * and to a record changed on the admin screen. Every failing field is reported at once, by its API name, so a whole
 * file can be corrected in one pass.
 *
 * <p>Values are forgiving about how they are typed, because they come from spreadsheets: a mobile number with spaces or
 * without its leading 0, a grade in lower case, {@code Unpaid leave} for {@code UNPAID_LEAVE}, a date as {@code
 * 2015-02-02} or {@code 02/02/2015}. They are strict about what they mean: a number that is not a Zimbabwean mobile, a
 * grade the grade-to-limit matrix does not know, or an engagement date in the future is refused.</p>
 */
public final class StaffRecordParser {

    static final String EMPLOYEE_NUMBER_PATTERN = "[A-Z0-9][A-Z0-9/-]{0,31}";
    static final String STATUSES = Arrays.stream(StaffEmploymentStatus.values()).map(Enum::name)
            .collect(Collectors.joining(", "));
    private static final DateTimeFormatter DAY_FIRST = DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private StaffRecordParser() {
    }

    /**
     * @param record      the record exactly as sent, keyed by {@link StaffFields} name
     * @param knownGrades the grades the grade-to-limit matrix has an approved limit for
     * @param today       today in the market's time zone
     */
    public static Parsed parse(Map<String, String> record, Set<String> knownGrades, LocalDate today) {
        Map<String, String> errors = new LinkedHashMap<>();

        String employeeNumber = upper(value(record, StaffFields.EMPLOYEE_NUMBER));
        if (employeeNumber == null) {
            errors.put(StaffFields.EMPLOYEE_NUMBER, "Employee number is required");
        } else if (!employeeNumber.matches(EMPLOYEE_NUMBER_PATTERN)) {
            errors.put(StaffFields.EMPLOYEE_NUMBER, "Employee number must be 1 to 32 letters, digits, hyphens or slashes");
        }

        String fullName = words(value(record, StaffFields.FULL_NAME));
        if (fullName == null) {
            errors.put(StaffFields.FULL_NAME, "Full name is required");
        } else if (fullName.length() > 160) {
            errors.put(StaffFields.FULL_NAME, "Full name must be at most 160 characters");
        }

        String rawId = value(record, StaffFields.NATIONAL_ID);
        String nationalId = rawId == null ? null : rawId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (StringUtils.isEmpty(nationalId)) {
            errors.put(StaffFields.NATIONAL_ID, "National ID is required");
        } else if (nationalId.length() < 6 || nationalId.length() > 20) {
            errors.put(StaffFields.NATIONAL_ID, "National ID must be 6 to 20 letters and digits, e.g. 63-2345678-B-42");
        }

        String msisdn = null;
        String rawMobile = phone(value(record, StaffFields.MOBILE_NUMBER));
        if (rawMobile == null) {
            errors.put(StaffFields.MOBILE_NUMBER, "Mobile number is required");
        } else if (!rawMobile.matches(MsisdnUtils.ZIMBABWE_MOBILE_REGEX)) {
            errors.put(StaffFields.MOBILE_NUMBER, "Mobile number " + MsisdnUtils.ZIMBABWE_MOBILE_MESSAGE);
        } else {
            msisdn = MsisdnUtils.formatMsisdnInternational(rawMobile);
        }

        String rawGrade = value(record, StaffFields.GRADE);
        String grade = StaffGrades.normalise(rawGrade);
        if (grade == null) {
            errors.put(StaffFields.GRADE, "Grade is required");
        } else if (!rawGrade.matches(StaffGrades.PATTERN)) {
            errors.put(StaffFields.GRADE, StaffGrades.MESSAGE);
        } else if (!knownGrades.contains(grade)) {
            errors.put(StaffFields.GRADE, "Grade " + grade + " is not in the grade-to-limit matrix");
        }

        String department = words(value(record, StaffFields.DEPARTMENT));
        if (department == null) {
            errors.put(StaffFields.DEPARTMENT, "Department is required");
        } else if (department.length() > 120) {
            errors.put(StaffFields.DEPARTMENT, "Department must be at most 120 characters");
        }

        StaffEmploymentStatus status = null;
        String rawStatus = value(record, StaffFields.EMPLOYMENT_STATUS);
        if (rawStatus == null) {
            errors.put(StaffFields.EMPLOYMENT_STATUS, "Employment status is required");
        } else {
            String name = rawStatus.toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
            status = Arrays.stream(StaffEmploymentStatus.values()).filter(s -> s.name().equals(name))
                    .findFirst().orElse(null);
            if (status == null) {
                errors.put(StaffFields.EMPLOYMENT_STATUS, "Employment status must be one of " + STATUSES);
            }
        }

        LocalDate engagementDate = null;
        String rawDate = value(record, StaffFields.ENGAGEMENT_DATE);
        if (rawDate == null) {
            errors.put(StaffFields.ENGAGEMENT_DATE, "Engagement date is required");
        } else {
            engagementDate = date(rawDate);
            if (engagementDate == null) {
                errors.put(StaffFields.ENGAGEMENT_DATE,
                        "Engagement date must be a date written yyyy-MM-dd or dd/MM/yyyy, e.g. 2015-02-02");
            } else if (engagementDate.isAfter(today)) {
                errors.put(StaffFields.ENGAGEMENT_DATE, "Engagement date cannot be in the future");
                engagementDate = null;
            }
        }

        String wallet = null;
        String rawWallet = phone(value(record, StaffFields.WALLET_ACCOUNT_NUMBER));
        if (rawWallet == null) {
            errors.put(StaffFields.WALLET_ACCOUNT_NUMBER, "Wallet or account number is required");
        } else if (rawWallet.matches(MsisdnUtils.ZIMBABWE_MOBILE_REGEX)) {
            wallet = MsisdnUtils.formatMsisdnInternational(rawWallet);
        } else if (rawWallet.matches("\\d{6,20}")) {
            wallet = rawWallet;
        } else {
            errors.put(StaffFields.WALLET_ACCOUNT_NUMBER,
                    "Wallet or account number must be an InnBucks wallet (a Zimbabwean mobile number) or 6 to 20"
                            + " digits");
        }

        String validEmployeeNumber = errors.containsKey(StaffFields.EMPLOYEE_NUMBER) ? null : employeeNumber;
        if (!errors.isEmpty()) {
            return new Parsed(null, errors, validEmployeeNumber, msisdn);
        }
        return new Parsed(new StaffRecord(employeeNumber, fullName, nationalId, msisdn, grade, department, status,
                engagementDate, wallet), Map.of(), employeeNumber, msisdn);
    }

    /**
     * A record as parsed: the record when every field passed, otherwise the reason for each one that did not.
     *
     * @param employeeNumber the employee number, normalised, whenever it is valid, even if other fields are not; so a
     *                       refused row is still checked for clashes with the rest of its file and the register
     * @param msisdn         likewise the mobile number, in international form
     */
    public record Parsed(StaffRecord record, Map<String, String> errors, String employeeNumber, String msisdn) {
        public boolean valid() {
            return record != null;
        }
    }

    private static String value(Map<String, String> record, String field) {
        return StringUtils.trimToNull(record.get(field));
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    /** Runs of whitespace collapsed to one space. */
    private static String words(String value) {
        return value == null ? null : value.replaceAll("\\s+", " ");
    }

    /** A number as typed in a spreadsheet: spaces, hyphens, dots and brackets dropped. */
    private static String phone(String value) {
        return value == null ? null : StringUtils.trimToNull(value.replaceAll("[\\s().-]", ""));
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException iso) {
            try {
                return LocalDate.parse(value, DAY_FIRST);
            } catch (DateTimeParseException dayFirst) {
                return null;
            }
        }
    }
}
