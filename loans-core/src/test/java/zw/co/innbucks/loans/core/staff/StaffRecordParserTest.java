package zw.co.innbucks.loans.core.staff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The staff register's rules for one record (FR-SGL-001, FR-SGL-003): forgiving about typing, strict about meaning. */
class StaffRecordParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Set<String> GRADES = Set.of("C3", "C4");

    private static Map<String, String> record() {
        Map<String, String> record = new HashMap<>();
        record.put(StaffFields.EMPLOYEE_NUMBER, " e1043 ");
        record.put(StaffFields.FULL_NAME, "  Tendai   Moyo ");
        record.put(StaffFields.NATIONAL_ID, "63-2345678-b-42");
        record.put(StaffFields.MOBILE_NUMBER, "077 123 4000");
        record.put(StaffFields.GRADE, " c4");
        record.put(StaffFields.DEPARTMENT, " Finance ");
        record.put(StaffFields.EMPLOYMENT_STATUS, "Unpaid leave");
        record.put(StaffFields.ENGAGEMENT_DATE, "02/02/2015");
        record.put(StaffFields.WALLET_ACCOUNT_NUMBER, "+263771234000");
        return record;
    }

    @Test
    @DisplayName("a record typed the way spreadsheets type it is normalised")
    void normalises() {
        StaffRecordParser.Parsed parsed = StaffRecordParser.parse(record(), GRADES, TODAY);

        assertThat(parsed.valid()).isTrue();
        assertThat(parsed.record()).isEqualTo(new StaffRecord("E1043", "Tendai Moyo", "632345678B42", "263771234000",
                "C4", "Finance", StaffEmploymentStatus.UNPAID_LEAVE, LocalDate.of(2015, 2, 2), "263771234000"));
        assertThat(parsed.employeeNumber()).isEqualTo("E1043");
        assertThat(parsed.msisdn()).isEqualTo("263771234000");
        assertThat(parsed.values()).isEqualTo(parsed.record().asText());
    }

    @Test
    @DisplayName("an ISO date, a bare mobile number and an account number of digits are accepted as they are")
    void otherSpellings() {
        Map<String, String> record = record();
        record.put(StaffFields.ENGAGEMENT_DATE, "2015-02-02");
        record.put(StaffFields.MOBILE_NUMBER, "771234000");
        record.put(StaffFields.WALLET_ACCOUNT_NUMBER, "1234-5678-90");
        record.put(StaffFields.EMPLOYMENT_STATUS, "active");

        StaffRecord parsed = StaffRecordParser.parse(record, GRADES, TODAY).record();

        assertThat(parsed.engagementDate()).isEqualTo(LocalDate.of(2015, 2, 2));
        assertThat(parsed.msisdn()).isEqualTo("263771234000");
        assertThat(parsed.walletAccountNumber()).isEqualTo("1234567890");
        assertThat(parsed.employmentStatus()).isEqualTo(StaffEmploymentStatus.ACTIVE);
        assertThat(StaffRecordParser.parse(Map.of(), GRADES, TODAY).errors()).hasSize(9);
    }

    @Test
    @DisplayName("every failing field is named at once, with the reason")
    void everyFailingField() {
        Map<String, String> record = new HashMap<>();
        record.put(StaffFields.EMPLOYEE_NUMBER, "E 1043");
        record.put(StaffFields.FULL_NAME, "x".repeat(161));
        record.put(StaffFields.NATIONAL_ID, "63-999");
        record.put(StaffFields.MOBILE_NUMBER, "12345");
        record.put(StaffFields.GRADE, "C9");
        record.put(StaffFields.DEPARTMENT, " ");
        record.put(StaffFields.EMPLOYMENT_STATUS, "Retired");
        record.put(StaffFields.ENGAGEMENT_DATE, "31/02/2015");
        record.put(StaffFields.WALLET_ACCOUNT_NUMBER, "abc");

        StaffRecordParser.Parsed parsed = StaffRecordParser.parse(record, GRADES, TODAY);

        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.errors()).containsExactly(
                Map.entry(StaffFields.EMPLOYEE_NUMBER, "Employee number must be 1 to 32 letters, digits, hyphens or"
                        + " slashes"),
                Map.entry(StaffFields.FULL_NAME, "Full name must be at most 160 characters"),
                Map.entry(StaffFields.NATIONAL_ID, "National ID must be 6 to 20 letters and digits, e.g."
                        + " 63-2345678-B-42"),
                Map.entry(StaffFields.MOBILE_NUMBER, "Mobile number must be a Zimbabwean mobile number, e.g."
                        + " 0772123123 or +263772123123"),
                Map.entry(StaffFields.GRADE, "Grade C9 is not in the grade-to-limit matrix"),
                Map.entry(StaffFields.DEPARTMENT, "Department is required"),
                Map.entry(StaffFields.EMPLOYMENT_STATUS, "Employment status must be one of ACTIVE, RESIGNED,"
                        + " TERMINATED, SUSPENDED, UNPAID_LEAVE"),
                Map.entry(StaffFields.ENGAGEMENT_DATE, "Engagement date must be a date written yyyy-MM-dd or"
                        + " dd/MM/yyyy, e.g. 2015-02-02"),
                Map.entry(StaffFields.WALLET_ACCOUNT_NUMBER, "Wallet or account number must be an InnBucks wallet (a"
                        + " Zimbabwean mobile number) or 6 to 20 digits"));
        assertThat(parsed.employeeNumber()).as("invalid, so not offered for clash checks").isNull();
        assertThat(parsed.msisdn()).isNull();
    }

    @Test
    @DisplayName("a valid employee number and mobile number are kept for clash checks even when other fields fail")
    void keysSurviveOtherErrors() {
        Map<String, String> record = record();
        record.put(StaffFields.GRADE, "Z1");

        StaffRecordParser.Parsed parsed = StaffRecordParser.parse(record, GRADES, TODAY);

        assertThat(parsed.valid()).isFalse();
        assertThat(parsed.errors()).containsOnlyKeys(StaffFields.GRADE);
        assertThat(parsed.employeeNumber()).isEqualTo("E1043");
        assertThat(parsed.msisdn()).isEqualTo("263771234000");
        assertThat(parsed.values()).as("every field that passed, normalised; the failing grade is not among them")
                .containsEntry(StaffFields.FULL_NAME, "Tendai Moyo")
                .containsEntry(StaffFields.EMPLOYMENT_STATUS, "UNPAID_LEAVE")
                .containsEntry(StaffFields.ENGAGEMENT_DATE, "2015-02-02")
                .doesNotContainKey(StaffFields.GRADE)
                .hasSize(8);
        assertThat(StaffRecordParser.parse(Map.of(StaffFields.EMPLOYEE_NUMBER, "e1"), GRADES, TODAY).values())
                .as("a field not sent has no value").containsOnlyKeys(StaffFields.EMPLOYEE_NUMBER);
    }

    @Test
    @DisplayName("an engagement date in the future and a malformed grade are refused")
    void futureDateAndMalformedGrade() {
        Map<String, String> record = record();
        record.put(StaffFields.ENGAGEMENT_DATE, "2026-10-02");
        record.put(StaffFields.GRADE, "C 4");

        assertThat(StaffRecordParser.parse(record, GRADES, TODAY).errors()).containsExactly(
                Map.entry(StaffFields.GRADE, StaffGrades.MESSAGE),
                Map.entry(StaffFields.ENGAGEMENT_DATE, "Engagement date cannot be in the future"));
        record.put(StaffFields.ENGAGEMENT_DATE, "2026-10-01");
        record.put(StaffFields.GRADE, "C4");
        assertThat(StaffRecordParser.parse(record, GRADES, TODAY).valid()).as("today is fine").isTrue();
    }
}
