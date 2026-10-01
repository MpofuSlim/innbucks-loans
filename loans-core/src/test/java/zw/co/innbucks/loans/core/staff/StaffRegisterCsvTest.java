package zw.co.innbucks.loans.core.staff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.exception.ValidationException;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reading a staff register file as HR's spreadsheets save it (FR-SGL-002). */
class StaffRegisterCsvTest {

    private static final String HEADER = "Employee No.,Full Name,National ID,Mobile,Grade,Department,Status,"
            + "Date Joined,Wallet";

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("headers match by name whatever their case and punctuation; unknown columns are ignored and reported")
    void headersByName() {
        StaffRegisterCsv.Sheet sheet = StaffRegisterCsv.read(utf8("﻿" + HEADER + ",Cost Centre\r\n"
                + "E1043,Tendai Moyo,63-2345678-B-42,0771234000,C4,Finance,ACTIVE,2015-02-02,0771234000,CC1\r\n"));

        assertThat(sheet.ignoredColumns()).containsExactly("Cost Centre");
        assertThat(sheet.rows()).singleElement().satisfies(row -> {
            assertThat(row.rowNumber()).isEqualTo(2);
            assertThat(row.values()).containsExactly(
                    java.util.Map.entry(StaffFields.EMPLOYEE_NUMBER, "E1043"),
                    java.util.Map.entry(StaffFields.FULL_NAME, "Tendai Moyo"),
                    java.util.Map.entry(StaffFields.NATIONAL_ID, "63-2345678-B-42"),
                    java.util.Map.entry(StaffFields.MOBILE_NUMBER, "0771234000"),
                    java.util.Map.entry(StaffFields.GRADE, "C4"),
                    java.util.Map.entry(StaffFields.DEPARTMENT, "Finance"),
                    java.util.Map.entry(StaffFields.EMPLOYMENT_STATUS, "ACTIVE"),
                    java.util.Map.entry(StaffFields.ENGAGEMENT_DATE, "2015-02-02"),
                    java.util.Map.entry(StaffFields.WALLET_ACCOUNT_NUMBER, "0771234000"));
        });
    }

    @Test
    @DisplayName("quoted cells may hold separators, doubled quotes and line breaks; row numbers stay on the file's lines")
    void quotedCells() {
        StaffRegisterCsv.Sheet sheet = StaffRegisterCsv.read(utf8(HEADER + "\n"
                + "E1,\"Chipo \"\"CJ\"\" Banda\",63-1,071,C4,\"Finance,\nTreasury\",ACTIVE,2018-01-15,1234567\n"
                + "\n"
                + "E2,Rudo,63-2,072,C4,Ops,ACTIVE,2018-01-15,1234567"));

        assertThat(sheet.rows()).extracting(StaffRegisterCsv.Row::rowNumber).containsExactly(2, 5);
        assertThat(sheet.rows().getFirst().values().get(StaffFields.FULL_NAME)).isEqualTo("Chipo \"CJ\" Banda");
        assertThat(sheet.rows().getFirst().values().get(StaffFields.DEPARTMENT)).isEqualTo("Finance,\nTreasury");
    }

    @Test
    @DisplayName("semicolon- and tab-separated files are read; a short row leaves its last fields blank")
    void otherSeparators() {
        StaffRegisterCsv.Sheet semicolons = StaffRegisterCsv.read(utf8(HEADER.replace(',', ';') + "\nE1;A B;63;"));
        assertThat(semicolons.rows().getFirst().values())
                .containsEntry(StaffFields.FULL_NAME, "A B")
                .containsEntry(StaffFields.MOBILE_NUMBER, "")
                .containsEntry(StaffFields.GRADE, null);
        StaffRegisterCsv.Sheet tabs = StaffRegisterCsv.read(utf8(HEADER.replace(',', '\t') + "\nE1\tA, B"));
        assertThat(tabs.rows().getFirst().values()).containsEntry(StaffFields.FULL_NAME, "A, B");
    }

    @Test
    @DisplayName("a first name and surname make the full name when there is no full name column")
    void firstNameAndSurname() {
        StaffRegisterCsv.Sheet sheet = StaffRegisterCsv.read(utf8("Employee Number,First Name,Surname,ID Number,"
                + "Mobile Number,Paterson Grade,Dept,Employment Status,Engagement Date,Account Number\n"
                + "E1,Tendai,Moyo,63,077,C4,Ops,ACTIVE,2015-02-02,123456"));

        assertThat(sheet.rows().getFirst().values())
                .containsEntry(StaffFields.FULL_NAME, "Tendai Moyo")
                .doesNotContainKeys("firstName", "lastName");
    }

    @Test
    @DisplayName("Excel's plain CSV (Windows-1252) is read as well as UTF-8")
    void windows1252() {
        byte[] content = (HEADER + "\nE1,Zoë Ncube,63,077,C4,Ops,ACTIVE,2015-02-02,123456")
                .getBytes(Charset.forName("windows-1252"));

        assertThat(StaffRegisterCsv.read(content).rows().getFirst().values())
                .containsEntry(StaffFields.FULL_NAME, "Zoë Ncube");
    }

    @Test
    @DisplayName("a file missing columns is refused whole, naming them and every column it needs")
    void missingColumns() {
        assertThatThrownBy(() -> StaffRegisterCsv.read(utf8("Employee No.,Full Name,National ID,Mobile,Status,"
                + "Date Joined,Wallet\nE1,A,63,077,ACTIVE,2015-02-02,1")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("The file has no column for grade, department. The columns it needs are: employee number,"
                        + " full name (or first name and surname), national ID, mobile number, grade, department,"
                        + " employment status, engagement date, wallet or account number");
        assertThatThrownBy(() -> StaffRegisterCsv.read(utf8(HEADER.replace("Full Name", "First Name") + "\nE1")))
                .as("a first name alone is not a full name")
                .hasMessageStartingWith("The file has no column for fullName.");
    }

    @Test
    @DisplayName("a file needing only some columns reads the others it has, and names only what it needs when refused")
    void requiredSubset() {
        StaffRegisterCsv.Sheet sheet = StaffRegisterCsv.read(utf8("Pay Point,Grade,Surname,Employee No.,First Name\n"
                + "Harare,C4,Moyo,E1043,Tendai\n"), java.util.Set.of(StaffFields.EMPLOYEE_NUMBER));

        assertThat(sheet.fields()).as("in the register's order")
                .containsExactly(StaffFields.EMPLOYEE_NUMBER, StaffFields.FULL_NAME, StaffFields.GRADE);
        assertThat(sheet.ignoredColumns()).containsExactly("Pay Point");
        assertThat(sheet.rows().getFirst().values()).containsExactly(
                java.util.Map.entry(StaffFields.EMPLOYEE_NUMBER, "E1043"),
                java.util.Map.entry(StaffFields.FULL_NAME, "Tendai Moyo"),
                java.util.Map.entry(StaffFields.GRADE, "C4"));
        assertThatThrownBy(() -> StaffRegisterCsv.read(utf8("Name,Grade\nTendai Moyo,C4"),
                java.util.Set.of(StaffFields.EMPLOYEE_NUMBER)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("The file has no column for employeeNumber. The columns it needs are: employee number");
        assertThat(StaffRegisterCsv.read(utf8(HEADER + "\nE1")).fields()).isEqualTo(StaffFields.ALL);
    }

    @Test
    @DisplayName("two columns read as the same field are refused")
    void ambiguousColumns() {
        assertThatThrownBy(() -> StaffRegisterCsv.read(utf8(HEADER + ",Phone\nE1")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Columns 'Mobile' and 'Phone' are both read as mobileNumber; remove one");
    }

    @Test
    @DisplayName("empty, header-only, oversized and unclosed-quote files are refused")
    void unreadableFiles() {
        assertThatThrownBy(() -> StaffRegisterCsv.read(new byte[0])).hasMessage("The file is empty");
        assertThatThrownBy(() -> StaffRegisterCsv.read(utf8(HEADER + "\n\n,,,\n")))
                .hasMessage("The file has a header but no staff rows");
        assertThatThrownBy(() -> StaffRegisterCsv.read(new byte[StaffRegisterCsv.MAX_BYTES + 1]))
                .hasMessage("The file is larger than 2 MB; split it into smaller files");
        assertThatThrownBy(() -> StaffRegisterCsv.read(utf8(HEADER + "\nE1,\"Tendai Moyo,63")))
                .hasMessage("Row 2 has a quote that is never closed");
        String rows = ("E1,A,63,077,C4,Ops,ACTIVE,2015-02-02,1\n").repeat(StaffRegisterCsv.MAX_ROWS + 1);
        assertThatThrownBy(() -> StaffRegisterCsv.read(utf8(HEADER + "\n" + rows)))
                .hasMessage("The file has more than 5000 staff rows; split it into smaller files");
    }
}
