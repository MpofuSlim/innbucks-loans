package zw.co.innbucks.loans.core.draft;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.document.DocumentInspector;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.IncompleteApplicationException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.files.DecodedFile;
import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanApplicationChecks;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;
import zw.co.innbucks.loans.core.loan.LoanApplicationResponse;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.core.loan.Witness;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Save-and-resume (FR-SSB-002). An application can be saved part-way, as often as the applicant likes,
 * and completed later; submitting it runs exactly what {@code POST /loans} runs, so the loan it becomes,
 * and its reference, are issued at that first submission.
 *
 * <p>A draft belongs to the user who started it and is invisible to anyone else. Each save is a JSON
 * merge patch: only the fields sent change, and a {@code null} clears one. What is saved is kept as an
 * application, so a value that is not one (a date that is not a date) is refused at the save that sends
 * it, while a field that is merely missing or not yet valid is reported on the draft, not refused. A
 * document is checked when saved (FR-SSB-005), so an unreadable photo is sent back while the applicant
 * still has the payslip in hand, not days later at submission.
 *
 * <p>An open draft holds the applicant's ID and payslip, so one not saved for
 * {@code loans.drafts.expiry-days} is deleted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanApplicationDraftService {

    /** Saved fields only: a field never sent is left out rather than kept as null. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static final Sort LAST_SAVED_FIRST = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));

    private final LoanApplicationDraftRepository draftRepository;
    private final LoanApplicationDraftDocumentRepository documentRepository;
    private final DocumentInspector documentInspector;
    private final LoanService loanService;
    private final AuthService authService;
    private final Validator validator;
    private final LoanApplicationDraftProperties properties;

    /** What one save changes, all of it checked before anything is written. */
    private record Save(String application, Map<DocumentType, DecodedFile> documents, Set<DocumentType> removedDocuments) {
    }

    /** Starts a draft for the caller, with whatever has been captured so far (possibly nothing). */
    @Transactional
    public LoanApplicationDraftResponse create(JsonNode application) {
        Save save = prepare(null, application);
        LocalDateTime now = now();
        LoanApplicationDraft draft = draftRepository.save(LoanApplicationDraft.builder()
                .ownerUserId(callerId())
                .status(LoanApplicationDraftStatus.OPEN)
                .application(save.application())
                .createdAt(now)
                .updatedAt(now)
                .build());
        storeDocuments(draft.getId(), save, now);
        log.info("Application draft {} started with {} document(s)", draft.getId(), save.documents().size());
        return view(draft);
    }

    /** Saves changes to one of the caller's open drafts. */
    @Transactional
    public LoanApplicationDraftResponse update(Long draftId, JsonNode changes) {
        LoanApplicationDraft draft = openDraftForUpdate(draftId);
        Save save = prepare(draft.getApplication(), changes);
        LocalDateTime now = now();
        draft.setApplication(save.application());
        draft.setUpdatedAt(now);
        storeDocuments(draftId, save, now);
        log.info("Application draft {} saved: {} document(s) replaced, {} removed", draftId, save.documents().size(),
                save.removedDocuments().size());
        return view(draft);
    }

    /** One of the caller's drafts: an open one to resume, or a submitted one naming the loan it became. */
    @Transactional(readOnly = true)
    public LoanApplicationDraftResponse get(Long draftId) {
        LoanApplicationDraft draft = ownedDraft(draftId);
        if (draft.getStatus() == LoanApplicationDraftStatus.OPEN) {
            requireNotExpired(draft);
        }
        return view(draft);
    }

    /** The caller's open drafts, last saved first. */
    @Transactional(readOnly = true)
    public Page<LoanApplicationDraftSummary> list(Pageable pageable) {
        Page<LoanApplicationDraft> drafts = draftRepository.findByOwnerUserIdAndStatusAndUpdatedAtGreaterThanEqual(
                callerId(), LoanApplicationDraftStatus.OPEN, expiryCutoff(),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LAST_SAVED_FIRST));
        Map<Long, List<DocumentType>> documents = drafts.isEmpty() ? Map.of()
                : documentRepository.findSummaries(drafts.map(LoanApplicationDraft::getId).getContent()).stream()
                .collect(Collectors.groupingBy(DraftDocumentSummary::draftId,
                        Collectors.mapping(DraftDocumentSummary::documentType, Collectors.toList())));
        return drafts.map(draft -> summary(draft, documents.getOrDefault(draft.getId(), List.of())));
    }

    /** A document saved with one of the caller's open drafts, with its content. */
    @Transactional(readOnly = true)
    public DraftDocumentContent document(Long draftId, DocumentType documentType) {
        LoanApplicationDraft draft = ownedDraft(draftId);
        requireOpen(draft);
        return documentRepository.findByDraftIdAndDocumentType(draftId, documentType)
                .map(DraftDocumentContent::of)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Draft %d has no %s saved", draftId, documentType)));
    }

    /** Deletes one of the caller's open drafts, with its documents. */
    @Transactional
    public void discard(Long draftId) {
        LoanApplicationDraft draft = openDraftForUpdate(draftId);
        documentRepository.deleteByDraftId(draftId);
        draftRepository.delete(draft);
        log.info("Application draft {} discarded", draftId);
    }

    /**
     * Submits one of the caller's open drafts as a loan application, through the same checks as
     * {@code POST /loans}. Refused, the draft is left exactly as it was, to be corrected and submitted
     * again. Accepted, the draft becomes a record of the loan it became, and the application and documents
     * it held are removed from it: the loan holds them now.
     *
     * @throws IncompleteApplicationException a field is still missing or invalid
     */
    @Transactional
    public LoanApplicationResponse submit(Long draftId) {
        LoanApplicationDraft draft = openDraftForUpdate(draftId);
        LoanApplicationRequest application = bind(JSON.readTree(draft.getApplication()));
        Map<String, String> validationErrors = validationErrors(application);
        if (!validationErrors.isEmpty()) {
            throw new IncompleteApplicationException(validationErrors);
        }
        List<LoanApplicationDraftDocument> documents = documentRepository.findByDraftId(draftId);
        documents.forEach(document -> attach(application, document));

        LoanApplicationResponse loan = loanService.requestLoan(application);

        LocalDateTime now = now();
        draft.setStatus(LoanApplicationDraftStatus.SUBMITTED);
        draft.setLoanId(loan.id());
        draft.setSubmittedAt(now);
        draft.setUpdatedAt(now);
        draft.setApplication(null);
        documentRepository.deleteAll(documents);
        log.info("Application draft {} submitted as loan {}", draftId, loan.id());
        return loan;
    }

    /** Deletes every open draft not saved within the expiry period. */
    @Transactional
    public int deleteExpired() {
        return draftRepository.deleteOpenLastSavedBefore(expiryCutoff());
    }

    // --- Saving ---

    /**
     * Applies the changes to what was saved, and checks the result: the fields bind as an application and
     * every document sent is accepted. Throws, having written nothing, when either does not.
     */
    private Save prepare(String savedApplication, JsonNode changes) {
        ObjectNode fields = changesAsObject(changes);
        Map<DocumentType, String> uploads = new EnumMap<>(DocumentType.class);
        Set<DocumentType> removed = EnumSet.noneOf(DocumentType.class);
        for (DocumentType type : DocumentType.values()) {
            takeDocument(fields, type, uploads, removed);
        }
        JsonNode saved = savedApplication == null ? JsonNodeFactory.instance.objectNode() : JSON.readTree(savedApplication);
        LoanApplicationRequest application = bind(JsonMergePatch.apply(saved, fields));
        Map<DocumentType, DecodedFile> documents = documentInspector.inspectAll(uploads);
        return new Save(JSON.writeValueAsString(withoutDocuments(application)), documents, removed);
    }

    private static ObjectNode changesAsObject(JsonNode changes) {
        if (changes == null || changes.isMissingNode() || changes.isNull()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!changes.isObject()) {
            throw new IllegalArgumentException("A draft is saved as a JSON object of application fields");
        }
        return (ObjectNode) changes.deepCopy();
    }

    /**
     * Moves a document out of the changes: a base64 string is an upload, and a null or empty one removes
     * the document. Removing the witness removes the witness's signature with it.
     */
    private static void takeDocument(ObjectNode fields, DocumentType type, Map<DocumentType, String> uploads,
                                     Set<DocumentType> removed) {
        String[] path = type.fieldName().split("\\.");
        ObjectNode holder = fields;
        for (int i = 0; i < path.length - 1; i++) {
            JsonNode parent = holder.get(path[i]);
            if (parent != null && parent.isNull()) {
                removed.add(type);
                return;
            }
            if (parent == null || !parent.isObject()) {
                return;
            }
            holder = (ObjectNode) parent;
        }
        JsonNode value = holder.remove(path[path.length - 1]);
        if (value == null) {
            return;
        }
        if (value.isNull() || (value.isString() && value.asString().isBlank())) {
            removed.add(type);
        } else if (value.isString()) {
            uploads.put(type, value.asString());
        } else {
            throw new IllegalArgumentException(
                    "Invalid value for '" + type.fieldName() + "': a document is sent as base64 text");
        }
    }

    private void storeDocuments(Long draftId, Save save, LocalDateTime now) {
        save.removedDocuments().forEach(type -> documentRepository.deleteByDraftIdAndDocumentType(draftId, type));
        save.documents().forEach((type, file) -> {
            LoanApplicationDraftDocument document = documentRepository.findByDraftIdAndDocumentType(draftId, type)
                    .orElseGet(() -> LoanApplicationDraftDocument.builder().draftId(draftId).documentType(type).build());
            document.setContent(file.content());
            document.setContentType(file.contentType());
            document.setSizeBytes(file.size());
            document.setSha256(file.sha256());
            document.setUploadedAt(now);
            documentRepository.save(document);
        });
    }

    // --- Reading ---

    private LoanApplicationDraftResponse view(LoanApplicationDraft draft) {
        if (draft.getStatus() == LoanApplicationDraftStatus.SUBMITTED) {
            return new LoanApplicationDraftResponse(draft.getId(), draft.getStatus(), null, null, null, null,
                    draft.getLoanId(), Loan.referenceOf(draft.getLoanId()), draft.getCreatedAt(), draft.getUpdatedAt(),
                    draft.getSubmittedAt());
        }
        JsonNode application = JSON.readTree(draft.getApplication());
        Map<String, String> validationErrors = validationErrors(bind(application));
        return new LoanApplicationDraftResponse(draft.getId(), draft.getStatus(), application,
                documentRepository.findSummaries(List.of(draft.getId())), validationErrors, validationErrors.isEmpty(),
                null, null, draft.getCreatedAt(), draft.getUpdatedAt(), null);
    }

    private LoanApplicationDraftSummary summary(LoanApplicationDraft draft, List<DocumentType> documents) {
        LoanApplicationRequest application = bind(JSON.readTree(draft.getApplication()));
        return new LoanApplicationDraftSummary(draft.getId(), application.getFirstName(), application.getLastName(),
                application.getEcNumber(), application.getMobileNumber(), application.getAmount(),
                application.getTenor(), validationErrors(application).size(), documents, draft.getCreatedAt(),
                draft.getUpdatedAt());
    }

    /**
     * What an application still needs, checked as {@code POST /loans} checks its body; every message for a
     * field, keyed by the field's path.
     */
    private Map<String, String> validationErrors(LoanApplicationRequest application) {
        Map<String, Set<String>> messages = new TreeMap<>();
        for (ConstraintViolation<LoanApplicationRequest> violation
                : validator.validate(application, Default.class, LoanApplicationChecks.class)) {
            messages.computeIfAbsent(violation.getPropertyPath().toString(), path -> new TreeSet<>())
                    .add(violation.getMessage());
        }
        Map<String, String> errors = new TreeMap<>();
        messages.forEach((path, pathMessages) -> errors.put(path, String.join("; ", pathMessages)));
        return errors;
    }

    // --- Binding ---

    /** The fields as an application; a value that cannot be one is refused, naming the field. */
    private static LoanApplicationRequest bind(JsonNode fields) {
        try {
            return JSON.treeToValue(fields, LoanApplicationRequest.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Invalid value for '" + fieldPath(e) + "'");
        }
    }

    private static String fieldPath(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : e.getPath()) {
            if (reference.getPropertyName() != null) {
                path.append(path.isEmpty() ? "" : ".").append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "application" : path.toString();
    }

    private static LoanApplicationRequest withoutDocuments(LoanApplicationRequest application) {
        application.setPayslipPicture(null);
        application.setNationalIdPicture(null);
        application.setSignature(null);
        if (application.getWitness() != null) {
            application.getWitness().setSignature(null);
        }
        return application;
    }

    private static void attach(LoanApplicationRequest application, LoanApplicationDraftDocument document) {
        String base64 = Base64.getEncoder().encodeToString(document.getContent());
        switch (document.getDocumentType()) {
            case PAYSLIP -> application.setPayslipPicture(base64);
            case NATIONAL_ID -> application.setNationalIdPicture(base64);
            case SIGNATURE -> application.setSignature(base64);
            case WITNESS_SIGNATURE -> {
                if (application.getWitness() == null) {
                    application.setWitness(new Witness());
                }
                application.getWitness().setSignature(base64);
            }
        }
    }

    // --- Ownership and lifetime ---

    /** The caller's draft; anyone else's is answered exactly like one that does not exist. */
    private LoanApplicationDraft ownedDraft(Long draftId) {
        Long callerId = callerId();
        return draftRepository.findById(draftId)
                .filter(draft -> draft.getOwnerUserId().equals(callerId))
                .orElseThrow(() -> notFound(draftId));
    }

    /** The caller's open draft, locked: two saves of one draft apply one after the other. */
    private LoanApplicationDraft openDraftForUpdate(Long draftId) {
        Long callerId = callerId();
        LoanApplicationDraft draft = draftRepository.findByIdForUpdate(draftId)
                .filter(found -> found.getOwnerUserId().equals(callerId))
                .orElseThrow(() -> notFound(draftId));
        requireOpen(draft);
        return draft;
    }

    private void requireOpen(LoanApplicationDraft draft) {
        if (draft.getStatus() == LoanApplicationDraftStatus.SUBMITTED) {
            throw new ConflictException(String.format("Draft %d was already submitted as loan %s", draft.getId(),
                    Loan.referenceOf(draft.getLoanId())));
        }
        requireNotExpired(draft);
    }

    private void requireNotExpired(LoanApplicationDraft draft) {
        if (draft.getUpdatedAt().isBefore(expiryCutoff())) {
            throw new NotFoundException(String.format(
                    "Draft %d has expired: a draft is kept for %d days after it was last saved", draft.getId(),
                    properties.getExpiryDays()));
        }
    }

    private static NotFoundException notFound(Long draftId) {
        return new NotFoundException(String.format("Draft %d not found", draftId));
    }

    private LocalDateTime expiryCutoff() {
        return now().minusDays(properties.getExpiryDays());
    }

    private Long callerId() {
        return authService.getLoggedInUser().getId();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
