package zw.co.innbucks.loans.core.staff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Reconciling the staff register against the HR payroll master (FR-SGL-008): what is matched, what each kind of
 * variance carries and in what order, what is deliberately not reported, and that the register itself is never
 * touched. In-memory repositories; today is 1 October 2026 in Harare, the matrix knows C3 and C4, and C4 lends 300.
 */
class StaffRegisterReconciliationServiceTest {

    private final List<StaffMember> members = new ArrayList<>();
    private final List<StaffRegisterReconciliation> reconciliations = new ArrayList<>();
    private final List<StaffRegisterVariance> variances = new ArrayList<>();
    private StaffMemberRepository memberRepository;
    private StaffRegisterVarianceRepository varianceRepository;
    private AuditService auditService;
    private StaffRegisterReconciliationService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(StaffMemberRepository.class);
        when(memberRepository.findAll(any(Sort.class))).thenAnswer(i -> members.stream()
                .sorted(Comparator.comparing(StaffMember::getEmployeeNumber)).toList());

        StaffRegisterReconciliationRepository reconciliationRepository =
                mock(StaffRegisterReconciliationRepository.class);
        when(reconciliationRepository.save(any())).thenAnswer(i -> {
            StaffRegisterReconciliation saved = i.getArgument(0);
            StaffRegisterReconciliation stored = saved.toBuilder().id((long) reconciliations.size() + 1).build();
            reconciliations.add(stored);
            return stored;
        });
        when(reconciliationRepository.findById(anyLong())).thenAnswer(i -> reconciliations.stream()
                .filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(reconciliationRepository.existsById(anyLong())).thenAnswer(i -> reconciliations.stream()
                .anyMatch(r -> r.getId().equals(i.getArgument(0))));
        when(reconciliationRepository.findAll(any(Pageable.class))).thenAnswer(i -> {
            Pageable pageable = i.getArgument(0);
            assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "id"));
            return new PageImpl<>(reconciliations.reversed(), pageable, reconciliations.size());
        });

        varianceRepository = mock(StaffRegisterVarianceRepository.class);
        when(varianceRepository.saveAll(any())).thenAnswer(i -> {
            Iterable<StaffRegisterVariance> saved = i.getArgument(0);
            List<StaffRegisterVariance> stored = new ArrayList<>();
            saved.forEach(variance -> {
                StaffRegisterVariance withId = variance.toBuilder().id((long) variances.size() + 1).build();
                variances.add(withId);
                stored.add(withId);
            });
            return stored;
        });
        when(varianceRepository.findByReconciliationIdOrderById(anyLong(), any())).thenAnswer(i ->
                page(variances.stream().filter(v -> v.getReconciliationId().equals(i.getArgument(0))).toList(),
                        i.getArgument(1)));
        when(varianceRepository.findByReconciliationIdAndKindOrderById(anyLong(), any(), any())).thenAnswer(i ->
                page(variances.stream().filter(v -> v.getReconciliationId().equals(i.getArgument(0))
                        && v.getKind() == i.getArgument(1)).toList(), i.getArgument(2)));

        StaffGradeLimitService gradeLimitService = mock(StaffGradeLimitService.class);
        when(gradeLimitService.recognisedGrades()).thenReturn(Set.of("C3", "C4"));
        when(gradeLimitService.limitsOn(LocalDate.of(2026, 10, 1))).thenReturn(Map.of(
                "C4", new StaffGradeLimit(1L, "C4", "Band C", new BigDecimal("300.00"), LocalDate.of(2026, 9, 1),
                        "credit2", LocalDateTime.of(2026, 9, 1, 8, 0))));
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUsername()).thenReturn("hc1");
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"), ZoneOffset.UTC);
        service = new StaffRegisterReconciliationService(memberRepository, reconciliationRepository,
                varianceRepository, gradeLimitService, authService, auditService, new MarketTimeZone("ZW", clock));
    }

    private static Page<StaffRegisterVariance> page(List<StaffRegisterVariance> found, Pageable pageable) {
        return new PageImpl<>(found.stream().sorted(Comparator.comparing(StaffRegisterVariance::getId)).toList(),
                pageable, found.size());
    }

    private void member(String employeeNumber, String fullName, String grade, String department,
                        StaffEmploymentStatus status) {
        String suffix = employeeNumber.substring(employeeNumber.length() - 3);
        members.add(StaffMember.builder().id((long) members.size() + 1).employeeNumber(employeeNumber)
                .fullName(fullName).nationalId("44123456K" + suffix).msisdn("263772000" + suffix).grade(grade)
                .department(department).employmentStatus(status).engagementDate(LocalDate.of(2012, 6, 11))
                .walletAccountNumber("263772000" + suffix).statusChangedAt(LocalDateTime.of(2026, 9, 1, 8, 0))
                .createdBatchId(1L).createdAt(LocalDateTime.of(2026, 9, 1, 8, 0)).updatedBatchId(1L)
                .updatedAt(LocalDateTime.of(2026, 9, 1, 8, 0)).build());
    }

    private static StaffRegisterReconciliationRequest payroll(String csv) {
        return StaffRegisterReconciliationRequest.builder().fileName(" payroll-2026-10.csv ").comment("October")
                .content(Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8))).build();
    }

    private List<StaffRegisterVarianceResponse> report(long reconciliationId) {
        return service.variances(reconciliationId, null, PageRequest.of(0, 100)).getContent();
    }

    @Test
    @DisplayName("every kind of variance is found, carries what it needs, and comes most urgent first")
    void everyKind() {
        member("E1001", "Nyasha Dube", "C4", "Credit", StaffEmploymentStatus.ACTIVE);
        member("E1002", "Chipo Banda", "C4", "Treasury", StaffEmploymentStatus.SUSPENDED);
        member("E1003", "Rudo Gumbo", "C4", "Finance", StaffEmploymentStatus.RESIGNED);
        member("E1004", "Tatenda Shumba", "C3", "Credit", StaffEmploymentStatus.ACTIVE);
        member("E1005", "Farai Moyo", "C3", "Operations", StaffEmploymentStatus.ACTIVE);
        member("E1006", "Kuda Dube", "C3", "Credit", StaffEmploymentStatus.ACTIVE);
        member("E1007", "Grace Banda", "B2", "Audit", StaffEmploymentStatus.ACTIVE);
        member("E1008", "Tsitsi Marufu", "C4", "Finance", StaffEmploymentStatus.ACTIVE);
        member("E1009", "Blessing Chari", "C4", "Finance", StaffEmploymentStatus.SUSPENDED);

        StaffRegisterReconciliationResponse run = service.reconcile(payroll(
                "Employee Number,Name,National ID,Mobile,Grade,Department,Status,Pay Point\n"
                        + "e1004,\"SHUMBA,  Tatenda\",44-123456-k-004,0772 000 004,c3,credit,Active,Harare\n"
                        + "E1005,Farai Moyo,44123456K005,12345,C4,,Active,Harare\n"
                        + "E1006,Kuda Dube,44123456K006,0772000006,C3,Credit,Active,Harare\n"
                        + "E2001,Sipho Ndlovu,44123456K201,0772000201,C3,Operations,Active,Bulawayo\n"
                        + ",Nobody Here,44123456K999,0772000999,C3,Operations,Active,Harare\n"
                        + "E1006,Kuda Dube,44123456K006,0772000006,C3,Credit,Active,Harare\n"
                        + "E2002,Old Leaver,44123456K202,0772000202,C3,Operations,Resigned,Harare\n"
                        + "E 1043,Typo Row,44123456K043,0772000043,C3,Operations,Active,Harare\n"
                        + "E1007,Grace Banda,44123456K007,0772000007,B2,Audit,ACTIVE,Harare\n"
                        + "E1009,Blessing Chari,44123456K009,0772000009,C4,Finance,Terminated,Harare\n"
                        + "E1008,Tsitsi Marufu,44123456K008,0772000008,C4,Finance,Resigned,Harare\n"));

        assertThat(run.id()).isEqualTo(1L);
        assertThat(run.fileName()).isEqualTo("payroll-2026-10.csv");
        assertThat(run.runBy()).isEqualTo("hc1");
        assertThat(run.comment()).isEqualTo("October");
        assertThat(run.comparedFields()).containsExactly(StaffFields.FULL_NAME, StaffFields.NATIONAL_ID,
                StaffFields.MOBILE_NUMBER, StaffFields.GRADE, StaffFields.DEPARTMENT, StaffFields.EMPLOYMENT_STATUS);
        assertThat(run.ignoredColumns()).containsExactly("Pay Point");
        assertThat(run.payrollRows()).isEqualTo(11);
        assertThat(run.registerMembers()).isEqualTo(9);
        assertThat(run.matched()).as("E1004 however typed, and E1007 on a grade only the register knows")
                .isEqualTo(2);
        assertThat(run.different()).isEqualTo(1);
        assertThat(run.leftOnPayroll()).as("E1008 and the suspended E1009, whom the payroll has as gone").isEqualTo(2);
        assertThat(run.leftOnPayrollEligible()).isEqualTo(1);
        assertThat(run.notOnRegister()).as("E2001; E2002 has left, so is nothing to act on").isEqualTo(1);
        assertThat(run.notOnPayroll()).as("E1001 and the suspended E1002; E1003 has left").isEqualTo(2);
        assertThat(run.notOnPayrollEligible()).isEqualTo(1);
        assertThat(run.duplicatesOnPayroll()).isEqualTo(1);
        assertThat(run.unreadableRows()).isEqualTo(2);
        assertThat(run.variances()).isEqualTo(9);
        assertThat(reconciliations.getFirst().getFileSha256()).hasSize(64);

        List<StaffRegisterVarianceResponse> report = report(1L);
        assertThat(report).extracting(StaffRegisterVarianceResponse::kind, StaffRegisterVarianceResponse::employeeNumber)
                .as("the most urgent kind first; among possible leavers, who could still borrow first")
                .containsExactly(
                        tuple(StaffRegisterVarianceKind.LEFT_ON_PAYROLL, "E1008"),
                        tuple(StaffRegisterVarianceKind.LEFT_ON_PAYROLL, "E1009"),
                        tuple(StaffRegisterVarianceKind.NOT_ON_PAYROLL, "E1001"),
                        tuple(StaffRegisterVarianceKind.NOT_ON_PAYROLL, "E1002"),
                        tuple(StaffRegisterVarianceKind.DIFFERENT, "E1005"),
                        tuple(StaffRegisterVarianceKind.NOT_ON_REGISTER, "E2001"),
                        tuple(StaffRegisterVarianceKind.DUPLICATE_ON_PAYROLL, "E1006"),
                        tuple(StaffRegisterVarianceKind.UNREADABLE, null),
                        tuple(StaffRegisterVarianceKind.UNREADABLE, "E 1043"));

        StaffRegisterVarianceResponse resigned = report.getFirst();
        assertThat(resigned.eligible()).isTrue();
        assertThat(resigned.rowNumbers()).containsExactly(12);
        assertThat(resigned.fullName()).isEqualTo("Tsitsi Marufu");
        assertThat(resigned.differences()).containsExactly(new StaffRegisterVarianceResponse.Difference(
                StaffFields.EMPLOYMENT_STATUS, "ACTIVE", "RESIGNED", null));
        assertThat(resigned.register()).isNull();
        assertThat(report.get(1).eligible()).as("suspended, so cannot borrow").isFalse();
        assertThat(report.get(1).rowNumbers()).containsExactly(11);

        report = report.subList(2, report.size());
        StaffRegisterVarianceResponse gone = report.getFirst();
        assertThat(gone.eligible()).as("ACTIVE on C4, which lends").isTrue();
        assertThat(gone.fullName()).isEqualTo("Nyasha Dube");
        assertThat(gone.register()).isEqualTo(members.getFirst().record().asText());
        assertThat(gone.rowNumbers()).isNull();
        assertThat(report.get(1).eligible()).as("suspended, so cannot borrow").isFalse();

        StaffRegisterVarianceResponse different = report.get(2);
        assertThat(different.rowNumbers()).containsExactly(3);
        assertThat(different.fullName()).isEqualTo("Farai Moyo");
        assertThat(different.differences()).containsExactly(
                new StaffRegisterVarianceResponse.Difference(StaffFields.MOBILE_NUMBER, "263772000005", "12345",
                        "Mobile number must be a Zimbabwean mobile number, e.g. 0772123123 or +263772123123"),
                new StaffRegisterVarianceResponse.Difference(StaffFields.GRADE, "C3", "C4", null),
                new StaffRegisterVarianceResponse.Difference(StaffFields.DEPARTMENT, "Operations", null,
                        StaffRegisterReconciliationService.BLANK));
        assertThat(different.register()).isNull();
        assertThat(different.eligible()).isNull();

        StaffRegisterVarianceResponse joiner = report.get(3);
        assertThat(joiner.rowNumbers()).containsExactly(5);
        assertThat(joiner.fullName()).isEqualTo("Sipho Ndlovu");
        assertThat(joiner.payroll()).as("as sent, without the ignored column").containsExactly(
                Map.entry(StaffFields.EMPLOYEE_NUMBER, "E2001"), Map.entry(StaffFields.FULL_NAME, "Sipho Ndlovu"),
                Map.entry(StaffFields.NATIONAL_ID, "44123456K201"), Map.entry(StaffFields.MOBILE_NUMBER, "0772000201"),
                Map.entry(StaffFields.GRADE, "C3"), Map.entry(StaffFields.DEPARTMENT, "Operations"),
                Map.entry(StaffFields.EMPLOYMENT_STATUS, "Active"));

        StaffRegisterVarianceResponse twice = report.get(4);
        assertThat(twice.rowNumbers()).containsExactly(4, 7);
        assertThat(twice.fullName()).as("the register's name").isEqualTo("Kuda Dube");
        assertThat(twice.differences()).as("not compared").isNull();

        assertThat(report.get(5).rowNumbers()).containsExactly(6);
        assertThat(report.get(5).fullName()).isEqualTo("Nobody Here");
        assertThat(report.get(5).errors()).containsExactly(
                Map.entry(StaffFields.EMPLOYEE_NUMBER, "Employee number is required"));
        assertThat(report.get(5).payroll()).containsEntry(StaffFields.EMPLOYEE_NUMBER, "");
        assertThat(report.get(6).errors()).containsExactly(Map.entry(StaffFields.EMPLOYEE_NUMBER,
                "Employee number must be 1 to 32 letters, digits, hyphens or slashes"));

        assertThat(service.variances(1L, StaffRegisterVarianceKind.UNREADABLE, PageRequest.of(0, 20)).getContent())
                .extracting(StaffRegisterVarianceResponse::rowNumbers)
                .containsExactly(List.of(6), List.of(9));
        verify(memberRepository, never()).save(any());
        verify(memberRepository, never()).saveAndFlush(any());
        verify(memberRepository, never()).lockRegister(anyLong());

        ArgumentCaptor<AuditLog.AuditLogBuilder> audit = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService).record(audit.capture());
        AuditLog logged = audit.getValue().build();
        assertThat(logged.getEventType()).isEqualTo(StaffRegisterReconciliationService.RECONCILED);
        assertThat(logged.getEntityId()).isEqualTo("1");
        assertThat(logged.getActorId()).isEqualTo("hc1");
        assertThat(logged.getDetail()).startsWith("file:payroll-2026-10.csv;sha256:")
                .endsWith(";rows:11;matched:2;different:1;leftOnPayroll:2;leftOnPayrollEligible:1;notOnRegister:1"
                        + ";notOnPayroll:2;notOnPayrollEligible:1;duplicates:1;unreadable:2");
    }

    @Test
    @DisplayName("with no status column, someone on the payroll whom the register has as RESIGNED is a difference")
    void leaverStillOnPayroll() {
        member("E1001", "Nyasha Dube", "C4", "Credit", StaffEmploymentStatus.ACTIVE);
        member("E1043", "Tendai Moyo", "C4", "Finance", StaffEmploymentStatus.RESIGNED);
        member("E1044", "Rudo Chikwanha", "C4", "Finance", StaffEmploymentStatus.TERMINATED);

        StaffRegisterReconciliationResponse run = service.reconcile(payroll("Staff No\nE1001\nE1043\n"));

        assertThat(run.comparedFields()).as("only who is on which").isEmpty();
        assertThat(run.matched()).isEqualTo(1);
        assertThat(run.notOnPayroll()).as("E1044 has left and is not on the payroll: as it should be").isZero();
        assertThat(report(1L)).singleElement().satisfies(variance -> {
            assertThat(variance.kind()).isEqualTo(StaffRegisterVarianceKind.DIFFERENT);
            assertThat(variance.employeeNumber()).isEqualTo("E1043");
            assertThat(variance.differences()).containsExactly(new StaffRegisterVarianceResponse.Difference(
                    StaffFields.EMPLOYMENT_STATUS, "RESIGNED", null,
                    "On the payroll master, but RESIGNED on the register"));
        });

        StaffRegisterReconciliationResponse withStatus = service.reconcile(payroll(
                "Staff No,Status\nE1001,Active\nE1043,Resigned\n"));
        assertThat(withStatus.matched()).as("a payroll that also has them as gone agrees with the register")
                .isEqualTo(2);
        assertThat(withStatus.variances()).isZero();
    }

    @Test
    @DisplayName("a payroll that agrees with the register is still kept, as the record that the month was done")
    void cleanRun() {
        member("E1001", "Nyasha Dube", "C4", "Credit", StaffEmploymentStatus.ACTIVE);

        StaffRegisterReconciliationResponse run = service.reconcile(payroll(
                "Employee No.,First Name,Surname,Grade,Department\nE1001,Nyasha,Dube,C4,CREDIT\n"));

        assertThat(run.variances()).isZero();
        assertThat(run.matched()).isEqualTo(1);
        assertThat(reconciliations).hasSize(1);
        assertThat(variances).isEmpty();
        assertThat(service.reconciliation(1L)).isEqualTo(new StaffRegisterReconciliationResponse(1L,
                "payroll-2026-10.csv", "hc1", reconciliations.getFirst().getRunAt(), "October",
                List.of(StaffFields.FULL_NAME, StaffFields.GRADE, StaffFields.DEPARTMENT), 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0,
                0, null));
    }

    @Test
    @DisplayName("a file with no usable employee number, or no base64, reconciles nothing")
    void nothingToReconcile() {
        member("E1001", "Nyasha Dube", "C4", "Credit", StaffEmploymentStatus.ACTIVE);

        assertThatThrownBy(() -> service.reconcile(payroll("Employee Number,Name\n,Nyasha Dube\nE 1,Tendai\n")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("No row of payroll-2026-10.csv has a usable employee number, so nothing was reconciled");
        assertThatThrownBy(() -> service.reconcile(payroll("Name,Grade\nNyasha Dube,C4\n")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("The file has no column for employeeNumber. The columns it needs are: employee number");
        assertThatThrownBy(() -> service.reconcile(StaffRegisterReconciliationRequest.builder()
                .fileName("payroll.csv").content("not base64!").build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("The file content is not base64");
        assertThat(reconciliations).isEmpty();
        assertThat(variances).isEmpty();
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("reconciliations list newest first; an unknown one is a 404, for itself and its report")
    void reads() {
        member("E1001", "Nyasha Dube", "C4", "Credit", StaffEmploymentStatus.ACTIVE);
        service.reconcile(payroll("Employee Number\nE1001\n"));
        service.reconcile(payroll("Employee Number\nE1002\n"));

        assertThat(service.reconciliations(PageRequest.of(0, 20)).getContent())
                .extracting(StaffRegisterReconciliationResponse::id).containsExactly(2L, 1L);
        assertThat(service.reconciliations(PageRequest.of(0, 20)).getContent())
                .allSatisfy(run -> assertThat(run.ignoredColumns()).isNull());
        assertThat(report(2L)).extracting(StaffRegisterVarianceResponse::kind).containsExactly(
                StaffRegisterVarianceKind.NOT_ON_PAYROLL, StaffRegisterVarianceKind.NOT_ON_REGISTER);
        assertThat(report(1L)).as("each report is its own").isEmpty();
        assertThatThrownBy(() -> service.reconciliation(99L)).isInstanceOf(NotFoundException.class)
                .hasMessage("Staff register reconciliation 99 not found");
        assertThatThrownBy(() -> service.variances(99L, null, PageRequest.of(0, 20)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("names agree whatever their case, punctuation and word order, but not with a word more or less")
    void names() {
        assertThat(StaffRegisterReconciliationService.nameKey("MOYO, Tendai"))
                .isEqualTo(StaffRegisterReconciliationService.nameKey("Tendai  Moyo"));
        assertThat(StaffRegisterReconciliationService.nameKey("O'Brien-Ncube, Mary"))
                .isEqualTo(StaffRegisterReconciliationService.nameKey("mary o'brien-ncube"));
        assertThat(StaffRegisterReconciliationService.nameKey("Tendai T. Moyo"))
                .isNotEqualTo(StaffRegisterReconciliationService.nameKey("Tendai Moyo"));
    }
}
