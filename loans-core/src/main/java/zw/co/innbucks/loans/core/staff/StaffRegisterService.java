package zw.co.innbucks.loans.core.staff;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Staff Register (FR-SGL-001 to FR-SGL-007): the master control for the Staff Grocery Loan, kept by Human Capital
 * under maker-checker.
 *
 * <p><b>Submitting.</b> A maker uploads a file (FR-SGL-002) or changes one record on the admin screen. Each row is
 * checked by {@link StaffRecordParser}; on top of that a file may not hold one employee number or one mobile number
 * twice, and a mobile number may not belong to a different employee on the register (FR-SGL-003). Refused rows are
 * reported with their reasons and the rest of the file is staged as one PENDING batch. Nothing reaches the register
 * yet.</p>
 *
 * <p><b>Deciding.</b> A different person approves or rejects the batch (FR-SGL-004); the database refuses a decision by
 * the submitter, who may withdraw it instead. Approval applies the staged rows in file order, keyed by employee number:
 * a new number adds a staff member, a known one changes theirs. Every row is checked again against the register as it
 * stands then, since other batches may have been approved since the upload: a row that no longer fits is SKIPPED with
 * its reason, and the rest are applied. Approvals take one register-wide lock, so two never interleave.</p>
 *
 * <p><b>History.</b> Every field a batch changes is recorded with its previous and new value, the submitter, the
 * approver and the time (FR-SGL-006). A new record is recorded the same way, from nothing.</p>
 *
 * <p><b>Eligibility.</b> Only ACTIVE staff whose grade has a limit above zero in force today may borrow (FR-SGL-005).
 * It is worked out when read, never stored, so a status change suppresses new offers the moment its batch is approved
 * (FR-SGL-007).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffRegisterService {

    static final String UPLOADED = "STAFF_REGISTER_UPLOADED";
    static final String SUBMITTED = "STAFF_REGISTER_CHANGE_SUBMITTED";
    static final String APPROVED = "STAFF_REGISTER_APPROVED";
    static final String REJECTED = "STAFF_REGISTER_REJECTED";
    static final String WITHDRAWN = "STAFF_REGISTER_WITHDRAWN";
    /** The advisory lock every approval takes: "STAFFREG". */
    static final long REGISTER_LOCK = 0x5354414646524547L;
    private static final String ENTITY = "STAFF_REGISTER_BATCH";
    private static final String CHANNEL = "admin-portal";
    private static final int MAX_STORED = 255;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StaffMemberRepository memberRepository;
    private final StaffRegisterBatchRepository batchRepository;
    private final StaffRegisterRowRepository rowRepository;
    private final StaffMemberChangeRepository changeRepository;
    private final StaffGradeLimitService gradeLimitService;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * Stages a staff register file for approval. Rows the register's rules refuse are reported and the rest staged.
     *
     * @throws ValidationException          the content is not base64, or the file cannot be read as a register
     * @throws StaffUploadRejectedException every row was refused, so there is nothing to submit
     */
    @Transactional
    public StaffRegisterBatchResponse upload(StaffRegisterUploadRequest request) {
        byte[] content;
        try {
            content = Base64.getMimeDecoder().decode(request.getContent());
        } catch (IllegalArgumentException notBase64) {
            throw new ValidationException("The file content is not base64");
        }
        StaffRegisterCsv.Sheet sheet = StaffRegisterCsv.read(content);
        List<Assessed> assessed = assess(sheet.rows());
        String fileName = request.getFileName().strip();
        if (assessed.stream().noneMatch(Assessed::staged)) {
            throw new StaffUploadRejectedException(fileName, assessed.stream().map(Assessed::response).toList());
        }
        String username = authService.getLoggedInUsername();
        StaffRegisterBatch batch = saveBatch(StaffRegisterBatchSource.UPLOAD, fileName,
                AuditService.sha256Hex(content), request.getComment(), assessed, username);
        log.info("Staff register batch {} uploaded by {}: {} rows, {} staged, {} refused", batch.getId(), username,
                batch.getTotalRows(), batch.getStagedRows(), batch.getRejectedRows());
        audit(UPLOADED, batch, username, "file:" + fileName + ";sha256:" + batch.getFileSha256() + ";rows:"
                + batch.getTotalRows() + ";staged:" + batch.getStagedRows() + ";refused:" + batch.getRejectedRows());
        return StaffRegisterBatchResponse.of(batch, sheet.ignoredColumns(), assessed.stream()
                .filter(row -> !row.staged()).map(Assessed::response).toList());
    }

    /**
     * Stages one staff record, added or changed on the admin screen, for approval.
     *
     * @throws StaffRecordInvalidException the record fails the register's rules
     * @throws ValidationException         the employee's record already holds exactly these values
     */
    @Transactional
    public StaffRegisterBatchResponse submit(StaffRecordRequest request) {
        Assessed row = assess(List.of(new StaffRegisterCsv.Row(1, request.values()))).getFirst();
        if (!row.staged()) {
            throw new StaffRecordInvalidException(row.errors());
        }
        if (row.member() != null && diff(row.member().record(), row.record()).isEmpty()) {
            throw new ValidationException(String.format("Employee %s's record already holds exactly these values;"
                    + " there is nothing to change", row.record().employeeNumber()));
        }
        String username = authService.getLoggedInUsername();
        StaffRegisterBatch batch = saveBatch(StaffRegisterBatchSource.MANUAL, null, null, request.getComment(),
                List.of(row), username);
        log.info("Staff register batch {} submitted by {}: {} employee {}", batch.getId(), username,
                row.action() == StaffRegisterRowAction.CREATE ? "add" : "change", row.record().employeeNumber());
        audit(SUBMITTED, batch, username, "employee:" + row.record().employeeNumber() + ";action:" + row.action());
        return StaffRegisterBatchResponse.of(batch);
    }

    /**
     * Approves or rejects a batch; never by whoever submitted it. Approval applies its staged rows to the register.
     *
     * @throws NotFoundException     no such batch
     * @throws ConflictException     it is no longer pending
     * @throws AccessDeniedException the caller submitted it
     * @throws ValidationException   a rejection without a reason
     */
    @Transactional
    public StaffRegisterBatchResponse decide(Long batchId, StaffRegisterDecisionRequest request) {
        StaffRegisterBatch batch = pendingForUpdate(batchId);
        String username = authService.getLoggedInUsername();
        if (StringUtils.equalsIgnoreCase(username, batch.getSubmittedBy())) {
            throw new AccessDeniedException(String.format("%s submitted staff register batch %d and cannot also"
                    + " approve or reject it; someone else in Human Capital or a SUPER_ADMIN must", username,
                    batchId));
        }
        String comment = StringUtils.trimToNull(request.getComment());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (request.getDecision() == StaffRegisterDecision.REJECTED) {
            if (comment == null) {
                throw new ValidationException("A reason is required to reject a staff register batch");
            }
            settle(batch, StaffRegisterBatchStatus.REJECTED, username, now, comment);
            log.info("Staff register batch {} rejected by {}", batchId, username);
            audit(REJECTED, batch, username, "reason:" + comment);
            return StaffRegisterBatchResponse.of(batch);
        }

        memberRepository.lockRegister(REGISTER_LOCK);
        Set<String> knownGrades = gradeLimitService.recognisedGrades();
        Map<StaffRegisterRowOutcome, Integer> counts = new HashMap<>();
        for (StaffRegisterRow row : rowRepository.findByBatchIdAndOutcomeOrderByRowNumber(batchId,
                StaffRegisterRowOutcome.STAGED)) {
            StaffRegisterRowOutcome outcome = apply(batch, row, knownGrades, username, now);
            counts.merge(outcome, 1, Integer::sum);
        }
        batch.setCreatedRows(counts.getOrDefault(StaffRegisterRowOutcome.CREATED, 0));
        batch.setAmendedRows(counts.getOrDefault(StaffRegisterRowOutcome.AMENDED, 0));
        batch.setUnchangedRows(counts.getOrDefault(StaffRegisterRowOutcome.UNCHANGED, 0));
        batch.setSkippedRows(counts.getOrDefault(StaffRegisterRowOutcome.SKIPPED, 0));
        settle(batch, StaffRegisterBatchStatus.APPROVED, username, now, comment);
        log.info("Staff register batch {} approved by {}: {} added, {} changed, {} unchanged, {} skipped", batchId,
                username, batch.getCreatedRows(), batch.getAmendedRows(), batch.getUnchangedRows(),
                batch.getSkippedRows());
        audit(APPROVED, batch, username, "created:" + batch.getCreatedRows() + ";amended:" + batch.getAmendedRows()
                + ";unchanged:" + batch.getUnchangedRows() + ";skipped:" + batch.getSkippedRows());
        return StaffRegisterBatchResponse.of(batch);
    }

    /**
     * Takes back a batch before anyone decides it. Only whoever submitted it may.
     *
     * @throws NotFoundException     no such batch
     * @throws ConflictException     it is no longer pending
     * @throws AccessDeniedException the caller did not submit it
     */
    @Transactional
    public StaffRegisterBatchResponse withdraw(Long batchId) {
        StaffRegisterBatch batch = pendingForUpdate(batchId);
        String username = authService.getLoggedInUsername();
        if (!StringUtils.equals(username, batch.getSubmittedBy())) {
            throw new AccessDeniedException(String.format("Only %s, who submitted staff register batch %d, can"
                    + " withdraw it; anyone else approves or rejects it", batch.getSubmittedBy(), batchId));
        }
        settle(batch, StaffRegisterBatchStatus.WITHDRAWN, username, LocalDateTime.now(ZoneOffset.UTC), null);
        log.info("Staff register batch {} withdrawn by {}", batchId, username);
        audit(WITHDRAWN, batch, username, null);
        return StaffRegisterBatchResponse.of(batch);
    }

    /** Batches, newest first; {@code status=PENDING} is the checker's queue. */
    @Transactional(readOnly = true)
    public Page<StaffRegisterBatchResponse> batches(StaffRegisterBatchStatus status, Pageable pageable) {
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<StaffRegisterBatch> page = status == null ? batchRepository.findAll(newestFirst)
                : batchRepository.findByStatus(status, newestFirst);
        return page.map(StaffRegisterBatchResponse::of);
    }

    /** @throws NotFoundException no such batch */
    @Transactional(readOnly = true)
    public StaffRegisterBatchResponse batch(Long batchId) {
        return StaffRegisterBatchResponse.of(batchRepository.findById(batchId)
                .orElseThrow(() -> batchNotFound(batchId)));
    }

    /**
     * A batch's rows in file order. A staged row shows what approving it would change in the register as it stands
     * now, so the checker sees exactly what they release.
     *
     * @throws NotFoundException no such batch
     */
    @Transactional(readOnly = true)
    public Page<StaffRegisterRowResponse> rows(Long batchId, StaffRegisterRowOutcome outcome, Pageable pageable) {
        if (!batchRepository.existsById(batchId)) {
            throw batchNotFound(batchId);
        }
        Page<StaffRegisterRow> page = outcome == null ? rowRepository.findByBatchIdOrderByRowNumber(batchId, pageable)
                : rowRepository.findByBatchIdAndOutcomeOrderByRowNumber(batchId, outcome, pageable);
        List<String> staged = page.getContent().stream()
                .filter(row -> row.getOutcome() == StaffRegisterRowOutcome.STAGED)
                .map(StaffRegisterRow::getEmployeeNumber).toList();
        Map<String, StaffMember> members = staged.isEmpty() ? Map.of()
                : memberRepository.findByEmployeeNumberIn(staged).stream()
                .collect(Collectors.toMap(StaffMember::getEmployeeNumber, Function.identity()));
        return page.map(row -> response(row, members));
    }

    /** The register, by employee number, with each member's eligibility today. */
    @Transactional(readOnly = true)
    public Page<StaffMemberResponse> members(StaffEmploymentStatus status, String grade, String department, String q,
                                             Pageable pageable) {
        Specification<StaffMember> filter = (root, query, cb) -> null;
        if (status != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("employmentStatus"), status));
        }
        if (StringUtils.isNotBlank(grade)) {
            String wanted = StaffGrades.normalise(grade);
            filter = filter.and((root, query, cb) -> cb.equal(root.get("grade"), wanted));
        }
        if (StringUtils.isNotBlank(department)) {
            String like = "%" + department.strip().toLowerCase(Locale.ROOT) + "%";
            filter = filter.and((root, query, cb) -> cb.like(cb.lower(root.get("department")), like));
        }
        if (StringUtils.isNotBlank(q)) {
            String text = q.strip().toLowerCase(Locale.ROOT);
            String digits = text.replaceAll("\\D", "");
            filter = filter.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("employeeNumber")), "%" + text + "%"),
                    cb.like(cb.lower(root.get("fullName")), "%" + text + "%"),
                    digits.length() >= 4 ? cb.like(root.get("msisdn"), "%" + digits + "%") : cb.disjunction()));
        }
        Pageable byEmployeeNumber = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by("employeeNumber"));
        Map<String, StaffGradeLimit> limits = gradeLimitService.limitsOn(marketTimeZone.today());
        return memberRepository.findAll(filter, byEmployeeNumber)
                .map(member -> StaffMemberResponse.of(member, limits.get(member.getGrade())));
    }

    /** @throws NotFoundException no such employee */
    @Transactional(readOnly = true)
    public StaffMemberResponse member(String employeeNumber) {
        StaffMember member = memberOrThrow(employeeNumber);
        return StaffMemberResponse.of(member,
                gradeLimitService.limitOn(member.getGrade(), marketTimeZone.today()).orElse(null));
    }

    /**
     * Every change to the employee's record, newest first (FR-SGL-006).
     *
     * @throws NotFoundException no such employee
     */
    @Transactional(readOnly = true)
    public List<StaffMemberChangeResponse> history(String employeeNumber) {
        return changeRepository.findByStaffMemberIdOrderByIdDesc(memberOrThrow(employeeNumber).getId()).stream()
                .map(StaffMemberChangeResponse::of)
                .toList();
    }

    /** One row checked against the rules, the rest of its file, and the register as it stands. */
    private record Assessed(int rowNumber, Map<String, String> sent, StaffRecord record, Map<String, String> errors,
                            StaffRegisterRowAction action, StaffMember member) {

        boolean staged() {
            return record != null && errors.isEmpty();
        }

        StaffRegisterRowResponse response() {
            return staged()
                    ? new StaffRegisterRowResponse(rowNumber, StaffRegisterRowOutcome.STAGED, action,
                    record.asText(), null, null)
                    : new StaffRegisterRowResponse(rowNumber, StaffRegisterRowOutcome.REJECTED, null, sent, errors,
                    null);
        }
    }

    private List<Assessed> assess(List<StaffRegisterCsv.Row> rows) {
        Set<String> knownGrades = gradeLimitService.recognisedGrades();
        LocalDate today = marketTimeZone.today();
        List<StaffRecordParser.Parsed> parsed = rows.stream()
                .map(row -> StaffRecordParser.parse(row.values(), knownGrades, today))
                .toList();
        Map<String, List<Integer>> byEmployee = new HashMap<>();
        Map<String, List<Integer>> byMobile = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            StaffRecordParser.Parsed row = parsed.get(i);
            if (row.employeeNumber() != null) {
                byEmployee.computeIfAbsent(row.employeeNumber(), key -> new ArrayList<>())
                        .add(rows.get(i).rowNumber());
            }
            if (row.msisdn() != null) {
                byMobile.computeIfAbsent(row.msisdn(), key -> new ArrayList<>()).add(rows.get(i).rowNumber());
            }
        }
        Map<String, StaffMember> members = byEmployee.isEmpty() ? Map.of()
                : memberRepository.findByEmployeeNumberIn(byEmployee.keySet()).stream()
                .collect(Collectors.toMap(StaffMember::getEmployeeNumber, Function.identity()));
        Map<String, String> mobileHolders = byMobile.isEmpty() ? Map.of()
                : memberRepository.findByMsisdnIn(byMobile.keySet()).stream()
                .collect(Collectors.toMap(StaffMember::getMsisdn, StaffMember::getEmployeeNumber));

        List<Assessed> assessed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            StaffRegisterCsv.Row row = rows.get(i);
            StaffRecordParser.Parsed parsedRow = parsed.get(i);
            StaffRecord record = parsedRow.record();
            Map<String, String> errors = new LinkedHashMap<>(parsedRow.errors());
            String employeeNumber = parsedRow.employeeNumber();
            String msisdn = parsedRow.msisdn();
            if (employeeNumber != null && byEmployee.get(employeeNumber).size() > 1) {
                errors.put(StaffFields.EMPLOYEE_NUMBER, String.format("Employee number %s appears more than once in"
                        + " the file (rows %s)", employeeNumber, joined(byEmployee.get(employeeNumber))));
            }
            if (msisdn != null) {
                List<Integer> sameMobile = byMobile.get(msisdn);
                String holder = mobileHolders.get(msisdn);
                if (sameMobile.size() > 1) {
                    errors.put(StaffFields.MOBILE_NUMBER, String.format("Mobile number %s appears more than once in"
                            + " the file (rows %s)", msisdn, joined(sameMobile)));
                } else if (holder != null && !holder.equals(employeeNumber)) {
                    errors.put(StaffFields.MOBILE_NUMBER, String.format("Mobile number %s already belongs to employee"
                            + " %s", msisdn, holder));
                }
            }
            StaffMember member = employeeNumber == null ? null : members.get(employeeNumber);
            StaffRegisterRowAction action = record == null ? null
                    : member == null ? StaffRegisterRowAction.CREATE : StaffRegisterRowAction.AMEND;
            assessed.add(new Assessed(row.rowNumber(), row.values(), errors.isEmpty() ? record : null, errors, action,
                    member));
        }
        return assessed;
    }

    private StaffRegisterBatch saveBatch(StaffRegisterBatchSource source, String fileName, String fileSha256,
                                         String comment, List<Assessed> rows, String username) {
        int staged = (int) rows.stream().filter(Assessed::staged).count();
        StaffRegisterBatch batch = batchRepository.save(StaffRegisterBatch.builder()
                .source(source)
                .fileName(fileName)
                .fileSha256(fileSha256)
                .status(StaffRegisterBatchStatus.PENDING)
                .submittedBy(username)
                .submittedAt(LocalDateTime.now(ZoneOffset.UTC))
                .submissionComment(StringUtils.trimToNull(comment))
                .totalRows(rows.size())
                .stagedRows(staged)
                .rejectedRows(rows.size() - staged)
                .build());
        List<StaffRegisterRow> stored = new ArrayList<>();
        for (Assessed row : rows) {
            Map<String, String> values = row.staged() ? row.record().asText() : row.sent();
            stored.add(StaffRegisterRow.builder()
                    .batchId(batch.getId())
                    .rowNumber(row.rowNumber())
                    .outcome(row.staged() ? StaffRegisterRowOutcome.STAGED : StaffRegisterRowOutcome.REJECTED)
                    .action(row.staged() ? row.action() : null)
                    .employeeNumber(stored(values, StaffFields.EMPLOYEE_NUMBER))
                    .fullName(stored(values, StaffFields.FULL_NAME))
                    .nationalId(stored(values, StaffFields.NATIONAL_ID))
                    .msisdn(stored(values, StaffFields.MOBILE_NUMBER))
                    .grade(stored(values, StaffFields.GRADE))
                    .department(stored(values, StaffFields.DEPARTMENT))
                    .employmentStatus(stored(values, StaffFields.EMPLOYMENT_STATUS))
                    .engagementDate(stored(values, StaffFields.ENGAGEMENT_DATE))
                    .walletAccountNumber(stored(values, StaffFields.WALLET_ACCOUNT_NUMBER))
                    .errors(row.staged() ? null : json(row.errors()))
                    .build());
        }
        rowRepository.saveAll(stored);
        return batch;
    }

    /**
     * Applies one staged row, re-checked against the register as it stands: another batch approved since the upload
     * may have taken the mobile number, in which case the row is skipped.
     */
    private StaffRegisterRowOutcome apply(StaffRegisterBatch batch, StaffRegisterRow row, Set<String> knownGrades,
                                          String approver, LocalDateTime now) {
        StaffRecord record = record(row);
        Map<String, String> errors = new LinkedHashMap<>();
        if (!knownGrades.contains(record.grade())) {
            errors.put(StaffFields.GRADE, "Grade " + record.grade() + " is no longer in the grade-to-limit matrix");
        }
        memberRepository.findByMsisdn(record.msisdn())
                .filter(holder -> !holder.getEmployeeNumber().equals(record.employeeNumber()))
                .ifPresent(holder -> errors.put(StaffFields.MOBILE_NUMBER, String.format("Mobile number %s now"
                        + " belongs to employee %s", record.msisdn(), holder.getEmployeeNumber())));
        if (!errors.isEmpty()) {
            row.setOutcome(StaffRegisterRowOutcome.SKIPPED);
            row.setErrors(json(errors));
            rowRepository.save(row);
            return StaffRegisterRowOutcome.SKIPPED;
        }

        Optional<StaffMember> existing = memberRepository.findByEmployeeNumberForUpdate(record.employeeNumber());
        StaffMember member;
        Map<String, String[]> changed;
        StaffRegisterRowOutcome outcome;
        if (existing.isEmpty()) {
            member = StaffMember.builder()
                    .createdBatchId(batch.getId())
                    .createdAt(now)
                    .statusChangedAt(now)
                    .build();
            changed = diff(null, record);
            row.setAction(StaffRegisterRowAction.CREATE);
            outcome = StaffRegisterRowOutcome.CREATED;
        } else {
            member = existing.get();
            changed = diff(member.record(), record);
            row.setAction(StaffRegisterRowAction.AMEND);
            outcome = changed.isEmpty() ? StaffRegisterRowOutcome.UNCHANGED : StaffRegisterRowOutcome.AMENDED;
            if (member.getEmploymentStatus() != record.employmentStatus()) {
                member.setStatusChangedAt(now);
            }
        }
        if (!changed.isEmpty()) {
            member.setEmployeeNumber(record.employeeNumber());
            member.setFullName(record.fullName());
            member.setNationalId(record.nationalId());
            member.setMsisdn(record.msisdn());
            member.setGrade(record.grade());
            member.setDepartment(record.department());
            member.setEmploymentStatus(record.employmentStatus());
            member.setEngagementDate(record.engagementDate());
            member.setWalletAccountNumber(record.walletAccountNumber());
            member.setUpdatedBatchId(batch.getId());
            member.setUpdatedAt(now);
            // Flushed now, so the next row's mobile-number check sees this one.
            member = memberRepository.saveAndFlush(member);
            Long memberId = member.getId();
            List<StaffMemberChange> history = new ArrayList<>();
            changed.forEach((field, values) -> history.add(StaffMemberChange.builder()
                    .staffMemberId(memberId)
                    .batchId(batch.getId())
                    .field(field)
                    .previousValue(values[0])
                    .newValue(values[1])
                    .submittedBy(batch.getSubmittedBy())
                    .approvedBy(approver)
                    .changedAt(now)
                    .build()));
            changeRepository.saveAll(history);
        }
        row.setOutcome(outcome);
        row.setStaffMemberId(member.getId());
        rowRepository.save(row);
        return outcome;
    }

    /** The fields that differ, each as {previous, new}; every field, from nothing, when there is no record yet. */
    private static Map<String, String[]> diff(StaffRecord before, StaffRecord after) {
        Map<String, String> old = before == null ? Map.of() : before.asText();
        Map<String, String[]> changed = new LinkedHashMap<>();
        after.asText().forEach((field, value) -> {
            String previous = old.get(field);
            if (!Objects.equals(previous, value)) {
                changed.put(field, new String[]{previous, value});
            }
        });
        return changed;
    }

    private StaffRegisterRowResponse response(StaffRegisterRow row, Map<String, StaffMember> members) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(StaffFields.EMPLOYEE_NUMBER, row.getEmployeeNumber());
        values.put(StaffFields.FULL_NAME, row.getFullName());
        values.put(StaffFields.NATIONAL_ID, row.getNationalId());
        values.put(StaffFields.MOBILE_NUMBER, row.getMsisdn());
        values.put(StaffFields.GRADE, row.getGrade());
        values.put(StaffFields.DEPARTMENT, row.getDepartment());
        values.put(StaffFields.EMPLOYMENT_STATUS, row.getEmploymentStatus());
        values.put(StaffFields.ENGAGEMENT_DATE, row.getEngagementDate());
        values.put(StaffFields.WALLET_ACCOUNT_NUMBER, row.getWalletAccountNumber());
        List<StaffRegisterRowResponse.FieldChange> changes = null;
        StaffRegisterRowAction action = row.getAction();
        if (row.getOutcome() == StaffRegisterRowOutcome.STAGED) {
            StaffMember member = members.get(row.getEmployeeNumber());
            action = member == null ? StaffRegisterRowAction.CREATE : StaffRegisterRowAction.AMEND;
            changes = new ArrayList<>();
            for (Map.Entry<String, String[]> change : diff(member == null ? null : member.record(), record(row))
                    .entrySet()) {
                changes.add(new StaffRegisterRowResponse.FieldChange(change.getKey(), change.getValue()[0],
                        change.getValue()[1]));
            }
        }
        return new StaffRegisterRowResponse(row.getRowNumber(), row.getOutcome(), action, values,
                errors(row.getErrors()), changes);
    }

    /** A staged row's values back as a record; they were normalised when it was staged. */
    private static StaffRecord record(StaffRegisterRow row) {
        return new StaffRecord(row.getEmployeeNumber(), row.getFullName(), row.getNationalId(), row.getMsisdn(),
                row.getGrade(), row.getDepartment(), StaffEmploymentStatus.valueOf(row.getEmploymentStatus()),
                LocalDate.parse(row.getEngagementDate()), row.getWalletAccountNumber());
    }

    private StaffRegisterBatch pendingForUpdate(Long batchId) {
        StaffRegisterBatch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> batchNotFound(batchId));
        if (batch.getStatus() != StaffRegisterBatchStatus.PENDING) {
            throw new ConflictException(String.format("Staff register batch %d is already %s", batchId,
                    batch.getStatus().name().toLowerCase(Locale.ROOT)));
        }
        return batch;
    }

    private void settle(StaffRegisterBatch batch, StaffRegisterBatchStatus status, String username, LocalDateTime at,
                        String comment) {
        batch.setStatus(status);
        batch.setDecidedBy(username);
        batch.setDecidedAt(at);
        batch.setDecisionComment(comment);
        batchRepository.save(batch);
    }

    private StaffMember memberOrThrow(String employeeNumber) {
        String wanted = StringUtils.upperCase(StringUtils.strip(employeeNumber), Locale.ROOT);
        return memberRepository.findByEmployeeNumber(wanted)
                .orElseThrow(() -> new NotFoundException("Employee " + wanted + " is not on the staff register"));
    }

    private static NotFoundException batchNotFound(Long batchId) {
        return new NotFoundException("Staff register batch " + batchId + " not found");
    }

    private static String stored(Map<String, String> values, String field) {
        String value = values.get(field);
        return value == null ? null : StringUtils.truncate(value, MAX_STORED);
    }

    private static String joined(List<Integer> rows) {
        return rows.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static String json(Map<String, String> errors) {
        return JSON.writeValueAsString(errors);
    }

    private static Map<String, String> errors(String json) {
        if (json == null) {
            return null;
        }
        JsonNode node = JSON.readTree(json);
        Map<String, String> errors = new LinkedHashMap<>();
        node.propertyNames().forEach(name -> errors.put(name, node.get(name).asString()));
        return errors;
    }

    private void audit(String eventType, StaffRegisterBatch batch, String actor, String detail) {
        auditService.record(AuditLog.builder()
                .eventType(eventType)
                .entityType(ENTITY).entityId(String.valueOf(batch.getId()))
                .actorId(actor).channelUsed(CHANNEL)
                .detail("source:" + batch.getSource() + (detail == null ? "" : ";" + detail)));
    }
}
