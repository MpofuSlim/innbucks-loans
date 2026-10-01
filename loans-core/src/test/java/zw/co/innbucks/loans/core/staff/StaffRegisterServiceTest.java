package zw.co.innbucks.loans.core.staff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The staff register under maker-checker (FR-SGL-001 to FR-SGL-007): what an upload stages and refuses, what a single
 * change submits, what approval applies and records, and who may decide or withdraw. In-memory repositories; today is
 * 1 October 2026 in Harare, and the matrix knows grades C3 and C4.
 */
class StaffRegisterServiceTest {

    private static final String HEADER = "Employee Number,Full Name,National ID,Mobile Number,Grade,Department,"
            + "Employment Status,Engagement Date,Wallet\n";

    private final List<StaffMember> members = new ArrayList<>();
    private final List<StaffRegisterBatch> batches = new ArrayList<>();
    private final List<StaffRegisterRow> rows = new ArrayList<>();
    private final List<StaffMemberChange> changes = new ArrayList<>();
    private StaffMemberRepository memberRepository;
    private AuthService authService;
    private AuditService auditService;
    private StaffGradeLimitService gradeLimitService;
    private StaffRegisterService service;

    @BeforeEach
    void setUp() {
        memberRepository = mock(StaffMemberRepository.class);
        when(memberRepository.findByEmployeeNumberIn(any())).thenAnswer(i -> {
            Collection<String> wanted = i.getArgument(0);
            return members.stream().filter(m -> wanted.contains(m.getEmployeeNumber())).toList();
        });
        when(memberRepository.findByMsisdnIn(any())).thenAnswer(i -> {
            Collection<String> wanted = i.getArgument(0);
            return members.stream().filter(m -> wanted.contains(m.getMsisdn())).toList();
        });
        when(memberRepository.findByMsisdn(any())).thenAnswer(i -> members.stream()
                .filter(m -> m.getMsisdn().equals(i.getArgument(0))).findFirst());
        when(memberRepository.findByEmployeeNumber(any())).thenAnswer(i -> members.stream()
                .filter(m -> m.getEmployeeNumber().equals(i.getArgument(0))).findFirst());
        when(memberRepository.findByEmployeeNumberForUpdate(any())).thenAnswer(i -> members.stream()
                .filter(m -> m.getEmployeeNumber().equals(i.getArgument(0))).findFirst());
        when(memberRepository.saveAndFlush(any())).thenAnswer(i -> {
            StaffMember member = i.getArgument(0);
            if (member.getId() == null) {
                member.setId((long) members.size() + 1);
                members.add(member);
            }
            return member;
        });

        StaffRegisterBatchRepository batchRepository = mock(StaffRegisterBatchRepository.class);
        when(batchRepository.save(any())).thenAnswer(i -> {
            StaffRegisterBatch batch = i.getArgument(0);
            if (batch.getId() == null) {
                batch.setId((long) batches.size() + 1);
                batches.add(batch);
            }
            return batch;
        });
        when(batchRepository.findByIdForUpdate(anyLong())).thenAnswer(i -> batch(i.getArgument(0)));
        when(batchRepository.findById(anyLong())).thenAnswer(i -> batch(i.getArgument(0)));
        when(batchRepository.existsById(anyLong())).thenAnswer(i -> batch(i.getArgument(0)).isPresent());

        StaffRegisterRowRepository rowRepository = mock(StaffRegisterRowRepository.class);
        when(rowRepository.saveAll(any())).thenAnswer(i -> {
            Iterable<StaffRegisterRow> saved = i.getArgument(0);
            saved.forEach(row -> {
                row.setId((long) rows.size() + 1);
                rows.add(row);
            });
            return saved;
        });
        when(rowRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(rowRepository.findByBatchIdAndOutcomeOrderByRowNumber(anyLong(), any())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getBatchId().equals(i.getArgument(0)) && r.getOutcome() == i.getArgument(1))
                .sorted(Comparator.comparing(StaffRegisterRow::getRowNumber)).toList());
        when(rowRepository.findByBatchIdOrderByRowNumber(anyLong(), any())).thenAnswer(i -> new PageImpl<>(
                rows.stream().filter(r -> r.getBatchId().equals(i.getArgument(0)))
                        .sorted(Comparator.comparing(StaffRegisterRow::getRowNumber)).toList(),
                (Pageable) i.getArgument(1), rows.size()));

        StaffMemberChangeRepository changeRepository = mock(StaffMemberChangeRepository.class);
        when(changeRepository.saveAll(any())).thenAnswer(i -> {
            Iterable<StaffMemberChange> saved = i.getArgument(0);
            saved.forEach(changes::add);
            return saved;
        });
        when(changeRepository.findByStaffMemberIdOrderByIdDesc(anyLong())).thenAnswer(i -> {
            List<StaffMemberChange> history = new ArrayList<>(changes.stream()
                    .filter(c -> c.getStaffMemberId().equals(i.getArgument(0))).toList());
            java.util.Collections.reverse(history);
            return history;
        });

        gradeLimitService = mock(StaffGradeLimitService.class);
        when(gradeLimitService.recognisedGrades()).thenReturn(Set.of("C3", "C4"));
        authService = mock(AuthService.class);
        as("hc1");
        auditService = mock(AuditService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-01T08:00:00Z"), ZoneOffset.UTC);
        service = new StaffRegisterService(memberRepository, batchRepository, rowRepository, changeRepository,
                gradeLimitService, authService, auditService, new MarketTimeZone("ZW", clock));
    }

    private Optional<StaffRegisterBatch> batch(Long id) {
        return batches.stream().filter(b -> b.getId().equals(id)).findFirst();
    }

    private void as(String username) {
        when(authService.getLoggedInUsername()).thenReturn(username);
    }

    private static StaffRegisterUploadRequest upload(String rows) {
        return StaffRegisterUploadRequest.builder().fileName("staff.csv").comment("October")
                .content(Base64.getEncoder().encodeToString((HEADER + rows).getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    private static StaffRecordRequest record(String employeeNumber, String mobile, String grade, String status) {
        return StaffRecordRequest.builder().employeeNumber(employeeNumber).fullName("Tendai Moyo")
                .nationalId("63-2345678-B-42").mobileNumber(mobile).grade(grade).department("Finance")
                .employmentStatus(status).engagementDate("2015-02-02").walletAccountNumber(mobile).build();
    }

    private static StaffRegisterDecisionRequest approve() {
        return StaffRegisterDecisionRequest.builder().decision(StaffRegisterDecision.APPROVED).build();
    }

    private StaffMember member(String employeeNumber, String msisdn, String grade, StaffEmploymentStatus status) {
        StaffMember member = StaffMember.builder().id((long) members.size() + 1).employeeNumber(employeeNumber)
                .fullName("Nyasha Dube").nationalId("08765432F21").msisdn(msisdn).grade(grade).department("Credit")
                .employmentStatus(status).engagementDate(LocalDate.of(2012, 6, 11)).walletAccountNumber(msisdn)
                .statusChangedAt(LocalDateTime.of(2026, 9, 1, 8, 0)).createdBatchId(0L)
                .createdAt(LocalDateTime.of(2026, 9, 1, 8, 0)).updatedBatchId(0L)
                .updatedAt(LocalDateTime.of(2026, 9, 1, 8, 0)).build();
        members.add(member);
        return member;
    }

    private List<String> auditedEvents() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> captor = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues().stream().map(builder -> builder.build().getEventType()).toList();
    }

    @Test
    @DisplayName("an upload stages the good rows, refuses the rest with reasons, and touches nothing on the register")
    void uploadStagesAndRefuses() {
        StaffRegisterBatchResponse batch = service.upload(upload(
                "E1043,Tendai Moyo,63-2345678-B-42,0771234000,C4,Finance,ACTIVE,2015-02-02,0771234000\n"
                        + "E1044,Rudo C,63-1112223-C-07,12345,C9,Ops,ACTIVE,2015-02-02,0771234001\n"));

        assertThat(batch.status()).isEqualTo(StaffRegisterBatchStatus.PENDING);
        assertThat(batch.totalRows()).isEqualTo(2);
        assertThat(batch.stagedRows()).isEqualTo(1);
        assertThat(batch.rejectedRows()).isEqualTo(1);
        assertThat(batch.ignoredColumns()).isEmpty();
        assertThat(batch.rejected()).singleElement().satisfies(row -> {
            assertThat(row.rowNumber()).isEqualTo(3);
            assertThat(row.values()).containsEntry(StaffFields.MOBILE_NUMBER, "12345");
            assertThat(row.errors()).containsOnlyKeys(StaffFields.MOBILE_NUMBER, StaffFields.GRADE);
        });
        assertThat(rows).extracting(StaffRegisterRow::getOutcome)
                .containsExactly(StaffRegisterRowOutcome.STAGED, StaffRegisterRowOutcome.REJECTED);
        assertThat(rows.getFirst().getMsisdn()).as("stored normalised").isEqualTo("263771234000");
        assertThat(rows.get(1).getErrors()).contains("Grade C9 is not in the grade-to-limit matrix");
        assertThat(batches.getFirst().getFileSha256()).hasSize(64);
        assertThat(members).isEmpty();
        verify(memberRepository, never()).saveAndFlush(any());
        assertThat(auditedEvents()).containsExactly(StaffRegisterService.UPLOADED);
    }

    @Test
    @DisplayName("a file may not hold one employee or one mobile number twice, nor a number another employee holds")
    void duplicatesAndHolders() {
        member("E1001", "263782606983", "C3", StaffEmploymentStatus.ACTIVE);

        StaffRegisterBatchResponse batch = service.upload(upload(
                "E1043,A B,63-2345678-B-42,0771234000,C4,Ops,ACTIVE,2015-02-02,0771234000\n"
                        + "e1043,A B,63-2345678-B-42,0771234009,C4,Ops,ACTIVE,2015-02-02,0771234000\n"
                        + "E1044,A B,63-2345678-B-42,771234005,C4,Ops,ACTIVE,2015-02-02,0771234000\n"
                        + "E1045,A B,63-2345678-B-42,0771234005,C9,Ops,ACTIVE,2015-02-02,0771234000\n"
                        + "E1046,A B,63-2345678-B-42,0782606983,C4,Ops,ACTIVE,2015-02-02,0771234000\n"
                        + "E1001,Nyasha Dube,08765432F21,0771234099,C4,Credit,ACTIVE,2012-06-11,0782606983\n"));

        Map<Integer, Map<String, String>> refused = new java.util.HashMap<>();
        batch.rejected().forEach(row -> refused.put(row.rowNumber(), row.errors()));
        assertThat(refused).containsOnlyKeys(2, 3, 4, 5, 6);
        assertThat(refused.get(2)).containsEntry(StaffFields.EMPLOYEE_NUMBER,
                "Employee number E1043 appears more than once in the file (rows 2, 3)");
        assertThat(refused.get(4)).containsEntry(StaffFields.MOBILE_NUMBER,
                "Mobile number 263771234005 appears more than once in the file (rows 4, 5)");
        assertThat(refused.get(5)).as("checked for clashes though its grade is also wrong")
                .containsKeys(StaffFields.GRADE, StaffFields.MOBILE_NUMBER);
        assertThat(refused.get(6)).containsEntry(StaffFields.MOBILE_NUMBER,
                "Mobile number 263782606983 already belongs to employee E1001");
        assertThat(rows.stream().filter(row -> row.getOutcome() == StaffRegisterRowOutcome.STAGED))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getRowNumber()).as("E1001 moving to a new number").isEqualTo(7);
                    assertThat(row.getAction()).isEqualTo(StaffRegisterRowAction.AMEND);
                });
    }

    @Test
    @DisplayName("an upload whose every row is refused submits nothing and says why for each row")
    void nothingToLoad() {
        assertThatThrownBy(() -> service.upload(upload(
                "E1043,Tendai Moyo,63-2345678-B-42,0771234000,C9,Finance,ACTIVE,2015-02-02,0771234000\n")))
                .isInstanceOf(StaffUploadRejectedException.class)
                .hasMessage("No row of staff.csv can be loaded; all 1 were refused")
                .satisfies(ex -> assertThat(((StaffUploadRejectedException) ex).getRows()).singleElement()
                        .satisfies(row -> assertThat(row.errors()).containsOnlyKeys(StaffFields.GRADE)));
        assertThat(batches).isEmpty();
        assertThat(rows).isEmpty();
        assertThatThrownBy(() -> service.upload(StaffRegisterUploadRequest.builder().fileName("x.csv")
                .content("not base64!").build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("The file content is not base64");
    }

    @Test
    @DisplayName("a single change is checked by the same rules, and refused when it would change nothing")
    void singleChange() {
        member("E1001", "263782606983", "C3", StaffEmploymentStatus.ACTIVE);

        assertThatThrownBy(() -> service.submit(record("E1002", "0782606983", "Z1", "ACTIVE")))
                .isInstanceOf(StaffRecordInvalidException.class)
                .satisfies(ex -> assertThat(((StaffRecordInvalidException) ex).getFields()).containsOnly(
                        Map.entry(StaffFields.GRADE, "Grade Z1 is not in the grade-to-limit matrix"),
                        Map.entry(StaffFields.MOBILE_NUMBER,
                                "Mobile number 263782606983 already belongs to employee E1001")));
        StaffRecordRequest same = StaffRecordRequest.builder().employeeNumber("e1001").fullName("Nyasha Dube")
                .nationalId("08-765432-F-21").mobileNumber("0782606983").grade("c3").department("Credit")
                .employmentStatus("Active").engagementDate("11/06/2012").walletAccountNumber("0782606983").build();
        assertThatThrownBy(() -> service.submit(same))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Employee E1001's record already holds exactly these values; there is nothing to change");

        StaffRegisterBatchResponse batch = service.submit(record("E1043", "0771234000", "C4", "ACTIVE"));
        assertThat(batch.source()).isEqualTo(StaffRegisterBatchSource.MANUAL);
        assertThat(batch.fileName()).isNull();
        assertThat(batch.stagedRows()).isEqualTo(1);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getRowNumber()).isEqualTo(1);
            assertThat(row.getAction()).isEqualTo(StaffRegisterRowAction.CREATE);
        });
        assertThat(auditedEvents()).containsExactly(StaffRegisterService.SUBMITTED);
    }

    @Test
    @DisplayName("the submitter can neither approve nor reject; a rejection needs a reason and applies nothing")
    void makerIsNotChecker() {
        Long id = service.submit(record("E1043", "0771234000", "C4", "ACTIVE")).id();

        for (StaffRegisterDecision decision : StaffRegisterDecision.values()) {
            assertThatThrownBy(() -> service.decide(id, StaffRegisterDecisionRequest.builder().decision(decision)
                    .comment("ok").build()))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessage("hc1 submitted staff register batch 1 and cannot also approve or reject it; someone"
                            + " else in Human Capital or a SUPER_ADMIN must");
        }
        as("hc2");
        assertThatThrownBy(() -> service.decide(id, StaffRegisterDecisionRequest.builder()
                .decision(StaffRegisterDecision.REJECTED).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A reason is required to reject a staff register batch");
        StaffRegisterBatchResponse rejected = service.decide(id, StaffRegisterDecisionRequest.builder()
                .decision(StaffRegisterDecision.REJECTED).comment("Wrong grade").build());

        assertThat(rejected.status()).isEqualTo(StaffRegisterBatchStatus.REJECTED);
        assertThat(rejected.decidedBy()).isEqualTo("hc2");
        assertThat(rejected.createdRows()).isNull();
        assertThat(members).isEmpty();
        verify(memberRepository, never()).lockRegister(anyLong());
        assertThatThrownBy(() -> service.decide(id, approve()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Staff register batch 1 is already rejected");
    }

    @Test
    @DisplayName("approval adds new staff, changes known ones, records every field, and counts what it did")
    void approvalApplies() {
        StaffMember known = member("E1001", "263782606983", "C3", StaffEmploymentStatus.ACTIVE);
        member("E1002", "263771234002", "C4", StaffEmploymentStatus.ACTIVE);
        Long id = service.upload(upload(
                "E1043,Tendai Moyo,63-2345678-B-42,0771234000,C4,Finance,ACTIVE,2015-02-02,0771234000\n"
                        + "E1001,Nyasha Dube,08765432F21,0782606983,C4,Credit,RESIGNED,2012-06-11,0782606983\n"
                        + "E1002,Nyasha Dube,08765432F21,0771234002,C4,Credit,ACTIVE,2012-06-11,0771234002\n")).id();
        as("hc2");

        StaffRegisterBatchResponse approved = service.decide(id, approve());

        assertThat(approved.status()).isEqualTo(StaffRegisterBatchStatus.APPROVED);
        assertThat(approved.createdRows()).isEqualTo(1);
        assertThat(approved.amendedRows()).isEqualTo(1);
        assertThat(approved.unchangedRows()).isEqualTo(1);
        assertThat(approved.skippedRows()).isZero();
        verify(memberRepository).lockRegister(StaffRegisterService.REGISTER_LOCK);
        assertThat(rows).extracting(StaffRegisterRow::getOutcome).containsExactly(StaffRegisterRowOutcome.CREATED,
                StaffRegisterRowOutcome.AMENDED, StaffRegisterRowOutcome.UNCHANGED);
        assertThat(rows).allSatisfy(row -> assertThat(row.getStaffMemberId()).isNotNull());

        StaffMember added = members.stream().filter(m -> m.getEmployeeNumber().equals("E1043")).findFirst()
                .orElseThrow();
        assertThat(added.getMsisdn()).isEqualTo("263771234000");
        assertThat(added.getCreatedBatchId()).isEqualTo(id);
        assertThat(known.getGrade()).isEqualTo("C4");
        assertThat(known.getEmploymentStatus()).isEqualTo(StaffEmploymentStatus.RESIGNED);
        assertThat(known.getStatusChangedAt()).as("status changed now")
                .isAfter(LocalDateTime.of(2026, 9, 1, 8, 0));
        assertThat(known.getUpdatedBatchId()).isEqualTo(id);

        assertThat(changes.stream().filter(c -> c.getStaffMemberId().equals(added.getId())))
                .hasSize(9)
                .allSatisfy(c -> {
                    assertThat(c.getPreviousValue()).isNull();
                    assertThat(c.getSubmittedBy()).isEqualTo("hc1");
                    assertThat(c.getApprovedBy()).isEqualTo("hc2");
                });
        assertThat(service.history("e1001")).extracting(StaffMemberChangeResponse::field,
                        StaffMemberChangeResponse::previousValue, StaffMemberChangeResponse::newValue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(StaffFields.EMPLOYMENT_STATUS, "ACTIVE", "RESIGNED"),
                        org.assertj.core.groups.Tuple.tuple(StaffFields.GRADE, "C3", "C4"));
        assertThat(service.history("E1002")).isEmpty();
        assertThat(auditedEvents()).containsExactly(StaffRegisterService.UPLOADED, StaffRegisterService.APPROVED);
    }

    @Test
    @DisplayName("a row whose mobile number another approval gave someone else since the upload is skipped, not applied")
    void rowsAreCheckedAgainOnApproval() {
        Long first = service.upload(upload(
                "E2000,New Starter,63-7777777-G-77,0778888888,C4,Ops,ACTIVE,2026-09-01,0778888888\n")).id();
        Long second = service.submit(record("E1043", "0778888888", "C4", "ACTIVE")).id();
        as("hc2");
        service.decide(first, approve());

        StaffRegisterBatchResponse approved = service.decide(second, approve());

        assertThat(approved.skippedRows()).isEqualTo(1);
        assertThat(approved.createdRows()).isZero();
        StaffRegisterRow skipped = rows.stream().filter(row -> row.getBatchId().equals(second)).findFirst()
                .orElseThrow();
        assertThat(skipped.getOutcome()).isEqualTo(StaffRegisterRowOutcome.SKIPPED);
        assertThat(skipped.getErrors()).contains("Mobile number 263778888888 now belongs to employee E2000");
        assertThat(members).extracting(StaffMember::getEmployeeNumber).containsExactly("E2000");
    }

    @Test
    @DisplayName("only the submitter withdraws a pending batch; a decided one stays decided")
    void withdraw() {
        Long id = service.submit(record("E1043", "0771234000", "C4", "ACTIVE")).id();
        as("hc2");

        assertThatThrownBy(() -> service.withdraw(id))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only hc1, who submitted staff register batch 1, can withdraw it; anyone else approves or"
                        + " rejects it");
        as("hc1");
        assertThat(service.withdraw(id).status()).isEqualTo(StaffRegisterBatchStatus.WITHDRAWN);
        assertThatThrownBy(() -> service.withdraw(id)).isInstanceOf(ConflictException.class)
                .hasMessage("Staff register batch 1 is already withdrawn");
        assertThatThrownBy(() -> service.withdraw(9L)).isInstanceOf(NotFoundException.class)
                .hasMessage("Staff register batch 9 not found");
    }

    @Test
    @DisplayName("a staged row shows what approving it would change now; a refused row shows why")
    void rowsShowTheirChanges() {
        member("E1001", "263782606983", "C3", StaffEmploymentStatus.ACTIVE);
        Long id = service.upload(upload(
                "E1001,Nyasha Dube,08765432F21,0782606983,C4,Credit,ACTIVE,2012-06-11,0782606983\n"
                        + "E1043,Tendai Moyo,63-2345678-B-42,0771234000,C4,Finance,ACTIVE,2015-02-02,0771234000\n"
                        + "E1044,Rudo,63-1112223-C-07,0771234001,C9,Ops,ACTIVE,2015-02-02,0771234001\n")).id();

        List<StaffRegisterRowResponse> listed = service.rows(id, null, PageRequest.of(0, 20)).getContent();

        assertThat(listed.get(0).action()).isEqualTo(StaffRegisterRowAction.AMEND);
        assertThat(listed.get(0).changes()).containsExactly(
                new StaffRegisterRowResponse.FieldChange(StaffFields.GRADE, "C3", "C4"));
        assertThat(listed.get(1).action()).isEqualTo(StaffRegisterRowAction.CREATE);
        assertThat(listed.get(1).changes()).hasSize(9).allSatisfy(change -> assertThat(change.from()).isNull());
        assertThat(listed.get(2).outcome()).isEqualTo(StaffRegisterRowOutcome.REJECTED);
        assertThat(listed.get(2).changes()).isNull();
        assertThat(listed.get(2).errors()).containsOnlyKeys(StaffFields.GRADE);
        assertThatThrownBy(() -> service.rows(9L, null, PageRequest.of(0, 20))).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("eligible means ACTIVE with a grade whose limit today is above zero; otherwise the reason says why")
    void eligibility() {
        StaffMember active = member("E1001", "263782606983", "C4", StaffEmploymentStatus.ACTIVE);
        StaffGradeLimit c4 = new StaffGradeLimit(1L, "C4", "Band C", new BigDecimal("300.00"),
                LocalDate.of(2026, 10, 1), "credit2", LocalDateTime.of(2026, 9, 30, 8, 0));
        StaffGradeLimit zero = new StaffGradeLimit(2L, "C4", "Band C", BigDecimal.ZERO.setScale(2),
                LocalDate.of(2026, 10, 1), "credit2", LocalDateTime.of(2026, 9, 30, 8, 0));

        assertThat(StaffMemberResponse.of(active, c4).eligible()).isTrue();
        assertThat(StaffMemberResponse.of(active, c4).ineligibleReason()).isNull();
        assertThat(StaffMemberResponse.of(active, null).ineligibleReason())
                .isEqualTo("Grade C4 has no limit in force today");
        assertThat(StaffMemberResponse.of(active, zero).ineligibleReason())
                .isEqualTo("Grade C4's limit is 0, so it is not lent to");
        active.setEmploymentStatus(StaffEmploymentStatus.SUSPENDED);
        StaffMemberResponse suspended = StaffMemberResponse.of(active, c4);
        assertThat(suspended.eligible()).isFalse();
        assertThat(suspended.ineligibleReason())
                .isEqualTo("Employment status is SUSPENDED; only ACTIVE staff may borrow");
        assertThat(suspended.mobileNumber()).isEqualTo("263782606983");

        when(gradeLimitService.limitOn("C4", LocalDate.of(2026, 10, 1))).thenReturn(Optional.of(c4));
        assertThat(service.member(" e1001 ").limit()).isEqualTo(c4);
        assertThatThrownBy(() -> service.member("E9999")).isInstanceOf(NotFoundException.class)
                .hasMessage("Employee E9999 is not on the staff register");
    }
}
