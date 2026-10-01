package zw.co.innbucks.loans.core.staff;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reconciles the Staff Register against the HR payroll master (FR-SGL-008). The register is the only credit control
 * for the Staff Grocery Loan, so if it falls behind the payroll the bank lends to people who have left; Human Capital
 * runs this each month with the payroll master and works the variance report it produces.
 *
 * <p><b>What is compared.</b> Employees are matched by employee number. The file needs only that column; every other
 * register field it has a column for is compared, so Human Capital chooses what is checked by what it exports. Values
 * are normalised by the register's own rules first ({@link StaffRecordParser}), so {@code 0771234000} matches {@code
 * 263771234000} and {@code 63-2345678-B-42} matches {@code 632345678B42}; a name matches whatever its case and word
 * order ("MOYO, Tendai" is Tendai Moyo), and a department whatever its case. A value the register's rules refuse, or a
 * blank cell, is reported as a difference with a note rather than compared.</p>
 *
 * <p><b>What is reported</b>, most urgent first: someone on the register as still employed whom the payroll's status
 * says has left; someone on the register as still employed who is not on the payroll at all, and has probably left (in
 * both, those who could still borrow come first); someone on both whose fields disagree; someone on the payroll as
 * still employed who is not on the register, and has probably joined; an employee number on more than one payroll row,
 * which is not compared since which row is right is unknown; and a row with no usable employee number. A payroll row
 * whose status says the person has left, for someone not on the register, is nothing to act on and is not reported.
 * When the payroll has no status column, someone it lists whom the register has as RESIGNED or TERMINATED is reported
 * as a difference, since being on the payroll suggests they still work here.</p>
 *
 * <p>It changes nothing on the register: correcting it is an ordinary batch, approved by someone else. Every run is
 * kept with its report, a clean one included, as the record that the month's reconciliation was done.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffRegisterReconciliationService {

    static final String RECONCILED = "STAFF_REGISTER_RECONCILED";
    static final String BLANK = "Blank on the payroll master";
    private static final String ENTITY = "STAFF_REGISTER_RECONCILIATION";
    private static final String CHANNEL = "admin-portal";
    private static final int MAX_STORED = 255;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StaffMemberRepository memberRepository;
    private final StaffRegisterReconciliationRepository reconciliationRepository;
    private final StaffRegisterVarianceRepository varianceRepository;
    private final StaffGradeLimitService gradeLimitService;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * Compares the register with a payroll master and keeps the report.
     *
     * @throws ValidationException the content is not base64, the file cannot be read or has no employee number column,
     *                             or no row has a usable employee number
     */
    @Transactional
    public StaffRegisterReconciliationResponse reconcile(StaffRegisterReconciliationRequest request) {
        byte[] content;
        try {
            content = Base64.getMimeDecoder().decode(request.getContent());
        } catch (IllegalArgumentException notBase64) {
            throw new ValidationException("The file content is not base64");
        }
        StaffRegisterCsv.Sheet sheet = StaffRegisterCsv.read(content, Set.of(StaffFields.EMPLOYEE_NUMBER));
        String fileName = request.getFileName().strip();
        List<String> compared = sheet.fields().stream()
                .filter(field -> !StaffFields.EMPLOYEE_NUMBER.equals(field))
                .toList();

        List<StaffMember> register = memberRepository.findAll(Sort.by("employeeNumber"));
        Map<String, StaffMember> onRegister = register.stream()
                .collect(Collectors.toMap(StaffMember::getEmployeeNumber, member -> member));
        // A grade the register already holds is never a payroll error, whatever the matrix says of it.
        Set<String> grades = new HashSet<>(gradeLimitService.recognisedGrades());
        register.forEach(member -> grades.add(member.getGrade()));
        LocalDate today = marketTimeZone.today();
        Map<String, StaffGradeLimit> limits = gradeLimitService.limitsOn(today);
        Predicate<StaffMember> eligible = member ->
                StaffMemberResponse.ineligibleReason(member, limits.get(member.getGrade())) == null;

        List<Line> unreadable = new ArrayList<>();
        Map<String, List<Line>> byEmployee = new LinkedHashMap<>();
        for (StaffRegisterCsv.Row row : sheet.rows()) {
            Line line = new Line(row, StaffRecordParser.parse(row.values(), grades, today));
            if (line.parsed().employeeNumber() == null) {
                unreadable.add(line);
            } else {
                byEmployee.computeIfAbsent(line.parsed().employeeNumber(), key -> new ArrayList<>()).add(line);
            }
        }
        if (byEmployee.isEmpty()) {
            throw new ValidationException(String.format("No row of %s has a usable employee number, so nothing was"
                    + " reconciled", fileName));
        }

        List<Found> left = new ArrayList<>();
        List<Found> different = new ArrayList<>();
        List<Found> notOnRegister = new ArrayList<>();
        List<Found> duplicates = new ArrayList<>();
        int matched = 0;
        for (Map.Entry<String, List<Line>> entry : byEmployee.entrySet()) {
            String employeeNumber = entry.getKey();
            List<Line> lines = entry.getValue();
            StaffMember member = onRegister.get(employeeNumber);
            Line line = lines.getFirst();
            if (lines.size() > 1) {
                duplicates.add(new Found(StaffRegisterVarianceKind.DUPLICATE_ON_PAYROLL, employeeNumber,
                        member != null ? member.getFullName() : line.name(), null,
                        new StaffRegisterVariance.Details(lines.stream().map(Line::rowNumber).toList(), null, null,
                                null, null)));
            } else if (member != null) {
                List<StaffRegisterVarianceResponse.Difference> differences = differences(member, line, compared);
                if (line.hasLeft() && !member.getEmploymentStatus().hasLeft()) {
                    left.add(new Found(StaffRegisterVarianceKind.LEFT_ON_PAYROLL, employeeNumber,
                            member.getFullName(), eligible.test(member), new StaffRegisterVariance.Details(
                            List.of(line.rowNumber()), null, null, differences, null)));
                } else if (differences.isEmpty()) {
                    matched++;
                } else {
                    different.add(new Found(StaffRegisterVarianceKind.DIFFERENT, employeeNumber,
                            member.getFullName(), null, new StaffRegisterVariance.Details(List.of(line.rowNumber()),
                            null, null, differences, null)));
                }
            } else if (!line.hasLeft()) {
                notOnRegister.add(new Found(StaffRegisterVarianceKind.NOT_ON_REGISTER, employeeNumber, line.name(),
                        null, new StaffRegisterVariance.Details(List.of(line.rowNumber()), null, line.sent(), null,
                        null)));
            }
        }

        List<Found> notOnPayroll = register.stream()
                .filter(member -> !member.getEmploymentStatus().hasLeft())
                .filter(member -> !byEmployee.containsKey(member.getEmployeeNumber()))
                .map(member -> new Found(StaffRegisterVarianceKind.NOT_ON_PAYROLL, member.getEmployeeNumber(),
                        member.getFullName(), eligible.test(member),
                        new StaffRegisterVariance.Details(null, member.record().asText(), null, null, null)))
                .toList();
        List<Found> unreadableFound = unreadable.stream()
                .map(line -> new Found(StaffRegisterVarianceKind.UNREADABLE,
                        StringUtils.trimToNull(line.row().values().get(StaffFields.EMPLOYEE_NUMBER)), line.name(),
                        null, new StaffRegisterVariance.Details(List.of(line.rowNumber()), null, line.sent(), null,
                        Map.of(StaffFields.EMPLOYEE_NUMBER,
                                line.parsed().errors().get(StaffFields.EMPLOYEE_NUMBER)))))
                .toList();

        String username = authService.getLoggedInUsername();
        StaffRegisterReconciliation reconciliation = reconciliationRepository.save(StaffRegisterReconciliation.builder()
                .fileName(fileName)
                .fileSha256(AuditService.sha256Hex(content))
                .runBy(username)
                .runAt(LocalDateTime.now(ZoneOffset.UTC))
                .comment(StringUtils.trimToNull(request.getComment()))
                .comparedFields(String.join(",", compared))
                .payrollRows(sheet.rows().size())
                .registerMembers(register.size())
                .matched(matched)
                .different(different.size())
                .leftOnPayroll(left.size())
                .leftOnPayrollEligible((int) left.stream().filter(Found::eligible).count())
                .notOnRegister(notOnRegister.size())
                .notOnPayroll(notOnPayroll.size())
                .notOnPayrollEligible((int) notOnPayroll.stream().filter(Found::eligible).count())
                .duplicatesOnPayroll(duplicates.size())
                .unreadableRows(unreadable.size())
                .build());
        // Most urgent kind first; among people who may have left, those who could still borrow first (a stable sort,
        // so otherwise in payroll row or employee number order).
        varianceRepository.saveAll(Stream.of(couldBorrowFirst(left), couldBorrowFirst(notOnPayroll), different,
                        notOnRegister, duplicates, unreadableFound)
                .flatMap(List::stream)
                .map(found -> found.variance(reconciliation.getId()))
                .toList());

        StaffRegisterReconciliationResponse response =
                StaffRegisterReconciliationResponse.of(reconciliation, sheet.ignoredColumns());
        log.info("Staff register reconciled by {} against a payroll master of {} rows: {} matched, {} different,"
                        + " {} left per the payroll ({} of them eligible), {} not on the register, {} not on the"
                        + " payroll ({} of them eligible), {} duplicated, {} unreadable", username,
                response.payrollRows(), response.matched(), response.different(), response.leftOnPayroll(),
                response.leftOnPayrollEligible(), response.notOnRegister(), response.notOnPayroll(),
                response.notOnPayrollEligible(), response.duplicatesOnPayroll(), response.unreadableRows());
        auditService.record(AuditLog.builder()
                .eventType(RECONCILED)
                .entityType(ENTITY).entityId(String.valueOf(reconciliation.getId()))
                .actorId(username).channelUsed(CHANNEL)
                .detail("file:" + fileName + ";sha256:" + reconciliation.getFileSha256() + ";rows:"
                        + response.payrollRows() + ";matched:" + response.matched() + ";different:"
                        + response.different() + ";leftOnPayroll:" + response.leftOnPayroll()
                        + ";leftOnPayrollEligible:" + response.leftOnPayrollEligible() + ";notOnRegister:"
                        + response.notOnRegister() + ";notOnPayroll:"
                        + response.notOnPayroll() + ";notOnPayrollEligible:" + response.notOnPayrollEligible()
                        + ";duplicates:" + response.duplicatesOnPayroll() + ";unreadable:"
                        + response.unreadableRows()));
        return response;
    }

    /** Reconciliations, newest first. */
    @Transactional(readOnly = true)
    public Page<StaffRegisterReconciliationResponse> reconciliations(Pageable pageable) {
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id"));
        return reconciliationRepository.findAll(newestFirst)
                .map(reconciliation -> StaffRegisterReconciliationResponse.of(reconciliation, null));
    }

    /** @throws NotFoundException no such reconciliation */
    @Transactional(readOnly = true)
    public StaffRegisterReconciliationResponse reconciliation(Long reconciliationId) {
        return StaffRegisterReconciliationResponse.of(reconciliationRepository.findById(reconciliationId)
                .orElseThrow(() -> notFound(reconciliationId)), null);
    }

    /**
     * A reconciliation's variance report, most urgent kind first; {@code kind} narrows it to one.
     *
     * @throws NotFoundException no such reconciliation
     */
    @Transactional(readOnly = true)
    public Page<StaffRegisterVarianceResponse> variances(Long reconciliationId, StaffRegisterVarianceKind kind,
                                                         Pageable pageable) {
        if (!reconciliationRepository.existsById(reconciliationId)) {
            throw notFound(reconciliationId);
        }
        Page<StaffRegisterVariance> page = kind == null
                ? varianceRepository.findByReconciliationIdOrderById(reconciliationId, pageable)
                : varianceRepository.findByReconciliationIdAndKindOrderById(reconciliationId, kind, pageable);
        return page.map(variance -> StaffRegisterVarianceResponse.of(variance,
                JSON.readValue(variance.getDetails(), StaffRegisterVariance.Details.class)));
    }

    /**
     * Each compared field the payroll disagrees on. A blank cell, or a value the register's rules refuse, is a
     * difference with a note; otherwise the normalised values are compared.
     */
    private static List<StaffRegisterVarianceResponse.Difference> differences(StaffMember member, Line line,
                                                                              List<String> compared) {
        Map<String, String> held = member.record().asText();
        List<StaffRegisterVarianceResponse.Difference> differences = new ArrayList<>();
        for (String field : compared) {
            String sent = StringUtils.trimToNull(line.row().values().get(field));
            String error = line.parsed().errors().get(field);
            if (sent == null) {
                differences.add(new StaffRegisterVarianceResponse.Difference(field, held.get(field), null, BLANK));
            } else if (error != null) {
                differences.add(new StaffRegisterVarianceResponse.Difference(field, held.get(field),
                        StringUtils.truncate(sent, MAX_STORED), error));
            } else if (!same(field, held.get(field), line.parsed().values().get(field))) {
                differences.add(new StaffRegisterVarianceResponse.Difference(field, held.get(field),
                        line.parsed().values().get(field), null));
            }
        }
        StaffEmploymentStatus status = member.getEmploymentStatus();
        if (!compared.contains(StaffFields.EMPLOYMENT_STATUS) && status.hasLeft()) {
            differences.add(new StaffRegisterVarianceResponse.Difference(StaffFields.EMPLOYMENT_STATUS, status.name(),
                    null, "On the payroll master, but " + status + " on the register"));
        }
        return differences;
    }

    private static List<Found> couldBorrowFirst(List<Found> found) {
        return found.stream().sorted(Comparator.comparing(each -> !each.eligible())).toList();
    }

    private static boolean same(String field, String held, String payroll) {
        return switch (field) {
            case StaffFields.FULL_NAME -> nameKey(held).equals(nameKey(payroll));
            case StaffFields.DEPARTMENT -> held.equalsIgnoreCase(payroll);
            default -> Objects.equals(held, payroll);
        };
    }

    /** A name's words in lower case and sorted, punctuation dropped: "MOYO, Tendai" and "Tendai Moyo" agree. */
    static String nameKey(String name) {
        return Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}'-]+"))
                .filter(word -> !word.isEmpty())
                .sorted()
                .collect(Collectors.joining(" "));
    }

    private static NotFoundException notFound(Long reconciliationId) {
        return new NotFoundException("Staff register reconciliation " + reconciliationId + " not found");
    }

    /** One payroll row, read by the register's rules. */
    private record Line(StaffRegisterCsv.Row row, StaffRecordParser.Parsed parsed) {

        int rowNumber() {
            return row.rowNumber();
        }

        /** The name as sent, spaces tidied; absent when the file has no name column or the cell is blank. */
        String name() {
            String name = StringUtils.trimToNull(row.values().get(StaffFields.FULL_NAME));
            return name == null ? null : StringUtils.truncate(name.replaceAll("\\s+", " "), MAX_STORED);
        }

        /** Whether its status says the person has left; never when the file has no status column. */
        boolean hasLeft() {
            String status = parsed.values().get(StaffFields.EMPLOYMENT_STATUS);
            return status != null && StaffEmploymentStatus.valueOf(status).hasLeft();
        }

        /** The row as sent, each value cut to what is stored. */
        Map<String, String> sent() {
            Map<String, String> sent = new LinkedHashMap<>();
            row.values().forEach((field, value) -> sent.put(field,
                    value == null ? null : StringUtils.truncate(value, MAX_STORED)));
            return sent;
        }
    }

    /** A variance before it is stored. */
    private record Found(StaffRegisterVarianceKind kind, String employeeNumber, String fullName, Boolean eligible,
                         StaffRegisterVariance.Details details) {

        StaffRegisterVariance variance(Long reconciliationId) {
            return StaffRegisterVariance.builder()
                    .reconciliationId(reconciliationId)
                    .kind(kind)
                    .employeeNumber(employeeNumber == null ? null : StringUtils.truncate(employeeNumber, MAX_STORED))
                    .fullName(fullName == null ? null : StringUtils.truncate(fullName, MAX_STORED))
                    .eligible(eligible)
                    .details(JSON.writeValueAsString(details))
                    .build();
        }
    }
}
