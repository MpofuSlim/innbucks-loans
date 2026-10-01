package zw.co.innbucks.loans.core.staff;

import org.apache.commons.lang3.StringUtils;
import zw.co.innbucks.loans.core.exception.ValidationException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a staff register file (FR-SGL-002): CSV with a header row, as Excel saves it. The header names the columns,
 * matched without regard to case, spaces or punctuation, and under the names HR spreadsheets commonly use, so "Employee
 * No.", "Mobile" and "Date Joined" all work. A full name may instead come as first name and surname columns. Columns
 * it does not know are ignored and reported.
 *
 * <p>Comma, semicolon and tab separators are all accepted (the header decides), quoted cells may hold separators,
 * quotes and line breaks, and the file may be UTF-8 (with or without Excel's byte-order mark) or Windows-1252, which is
 * what Excel's plain "CSV" saves. A file it cannot read at all is refused whole; a row it can read but not accept is
 * refused on its own, by the register's rules, and the rest loaded (FR-SGL-003).</p>
 */
public final class StaffRegisterCsv {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ROWS = 5000;

    private static final String FIRST_NAME = "firstName";
    private static final String LAST_NAME = "lastName";

    /** Header names, reduced to lower-case letters and digits, to the field they fill. */
    private static final Map<String, String> HEADERS = new LinkedHashMap<>();

    static {
        alias(StaffFields.EMPLOYEE_NUMBER, "employeenumber", "employeeno", "empno", "employeeid", "staffnumber",
                "staffno", "staffid", "payrollnumber", "payrollno");
        alias(StaffFields.FULL_NAME, "fullname", "name", "employeename", "staffname", "names");
        alias(FIRST_NAME, "firstname", "firstnames", "forename", "forenames", "givenname");
        alias(LAST_NAME, "surname", "lastname", "familyname");
        alias(StaffFields.NATIONAL_ID, "nationalid", "nationalidnumber", "nationalidno", "idnumber", "idno");
        alias(StaffFields.MOBILE_NUMBER, "mobilenumber", "mobile", "mobileno", "msisdn", "phone", "phonenumber",
                "cellnumber", "cellphone", "cell");
        alias(StaffFields.GRADE, "grade", "patersongrade", "jobgrade");
        alias(StaffFields.DEPARTMENT, "department", "dept", "division");
        alias(StaffFields.EMPLOYMENT_STATUS, "employmentstatus", "status", "staffstatus");
        alias(StaffFields.ENGAGEMENT_DATE, "engagementdate", "dateofengagement", "dateengaged", "datejoined",
                "joindate", "joiningdate", "startdate", "employmentdate");
        alias(StaffFields.WALLET_ACCOUNT_NUMBER, "walletaccountnumber", "walletaccount", "wallet", "walletnumber",
                "accountnumber", "account", "innbuckswallet", "innbucksaccount", "salaryaccount");
    }

    private static void alias(String field, String... names) {
        for (String name : names) {
            HEADERS.put(name, field);
        }
    }

    /** The column names a file needs, as the refusal names them. */
    static final String EXPECTED = "employee number, full name (or first name and surname), national ID, mobile"
            + " number, grade, department, employment status, engagement date, wallet or account number";

    private StaffRegisterCsv() {
    }

    /** A file as read: its rows, each keyed by {@link StaffFields} name, and the header cells it ignored. */
    public record Sheet(List<Row> rows, List<String> ignoredColumns) {
    }

    /** @param rowNumber the row's line in the file, the header being line 1 */
    public record Row(int rowNumber, Map<String, String> values) {
    }

    /** @throws ValidationException the file cannot be read as a staff register at all */
    public static Sheet read(byte[] content) {
        if (content == null || content.length == 0) {
            throw new ValidationException("The file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw new ValidationException("The file is larger than 2 MB; split it into smaller files");
        }
        String text = decode(content);
        List<Record> records = records(text, separator(text));
        if (records.isEmpty()) {
            throw new ValidationException("The file is empty");
        }

        Record header = records.getFirst();
        Map<Integer, String> columns = new LinkedHashMap<>();
        Map<String, String> headerOf = new LinkedHashMap<>();
        List<String> ignored = new ArrayList<>();
        for (int i = 0; i < header.cells().size(); i++) {
            String name = header.cells().get(i).strip();
            if (name.isEmpty()) {
                continue;
            }
            String field = HEADERS.get(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
            if (field == null) {
                ignored.add(name);
                continue;
            }
            if (headerOf.containsKey(field)) {
                throw new ValidationException(String.format("Columns '%s' and '%s' are both read as %s; remove one",
                        headerOf.get(field), name, field));
            }
            headerOf.put(field, name);
            columns.put(i, field);
        }
        boolean splitName = !headerOf.containsKey(StaffFields.FULL_NAME)
                && headerOf.containsKey(FIRST_NAME) && headerOf.containsKey(LAST_NAME);
        List<String> missing = StaffFields.ALL.stream()
                .filter(field -> !headerOf.containsKey(field)
                        && !(StaffFields.FULL_NAME.equals(field) && splitName))
                .toList();
        if (!missing.isEmpty()) {
            throw new ValidationException(String.format("The file has no column for %s. The columns it needs are: %s",
                    String.join(", ", missing), EXPECTED));
        }

        List<Row> rows = new ArrayList<>();
        for (Record record : records.subList(1, records.size())) {
            if (record.cells().stream().allMatch(String::isBlank)) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                throw new ValidationException(String.format("The file has more than %d staff rows; split it into"
                        + " smaller files", MAX_ROWS));
            }
            Map<String, String> values = new LinkedHashMap<>();
            columns.forEach((index, field) ->
                    values.put(field, index < record.cells().size() ? record.cells().get(index) : null));
            if (splitName) {
                String full = (StringUtils.defaultString(values.remove(FIRST_NAME)) + " "
                        + StringUtils.defaultString(values.remove(LAST_NAME))).strip();
                values.put(StaffFields.FULL_NAME, full);
            } else {
                values.remove(FIRST_NAME);
                values.remove(LAST_NAME);
            }
            Map<String, String> ordered = new LinkedHashMap<>();
            StaffFields.ALL.forEach(field -> ordered.put(field, values.get(field)));
            rows.add(new Row(record.line(), ordered));
        }
        if (rows.isEmpty()) {
            throw new ValidationException("The file has a header but no staff rows");
        }
        return new Sheet(List.copyOf(rows), List.copyOf(ignored));
    }

    /** UTF-8 (without its byte-order mark), else Windows-1252, which is what Excel's plain CSV is. */
    private static String decode(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException notUtf8) {
            text = new String(content, Charset.forName("windows-1252"));
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    /** Whichever of comma, semicolon and tab the header line uses most. */
    private static char separator(String text) {
        int end = text.indexOf('\n');
        String header = end < 0 ? text : text.substring(0, end);
        char best = ',';
        long most = -1;
        for (char candidate : new char[]{',', ';', '\t'}) {
            long count = header.chars().filter(c -> c == candidate).count();
            if (count > most) {
                most = count;
                best = candidate;
            }
        }
        return best;
    }

    private record Record(int line, List<String> cells) {
    }

    /** RFC 4180: quoted cells may hold the separator, doubled quotes and line breaks. */
    private static List<Record> records(String text, char separator) {
        List<Record> records = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordLine = 1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    cell.append(c);
                }
            } else if (c == '"' && cell.toString().isBlank()) {
                cell.setLength(0);
                quoted = true;
            } else if (c == separator) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                cell.setLength(0);
                records.add(new Record(recordLine, List.copyOf(cells)));
                cells.clear();
                line++;
                recordLine = line;
            } else {
                cell.append(c);
            }
            i++;
        }
        if (quoted) {
            throw new ValidationException(String.format("Row %d has a quote that is never closed", recordLine));
        }
        if (!cells.isEmpty() || !cell.isEmpty()) {
            cells.add(cell.toString());
            records.add(new Record(recordLine, List.copyOf(cells)));
        }
        return records;
    }
}
