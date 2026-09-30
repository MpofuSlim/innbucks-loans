package zw.co.innbucks.loans.core.draft;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.document.DocumentInspector;
import zw.co.innbucks.loans.core.document.DocumentProblemReason;
import zw.co.innbucks.loans.core.document.DocumentRejectedException;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.document.DocumentUploadProperties;
import zw.co.innbucks.loans.core.document.TestDocuments;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.IncompleteApplicationException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.files.FileSignatureValidator;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;
import zw.co.innbucks.loans.core.loan.LoanApplicationResponse;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.core.user.User;

import java.awt.Color;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Save-and-resume (FR-SSB-002): a draft is saved a few fields at a time, reports what it still needs,
 * checks its documents when they are saved, belongs to one user, and is submitted through exactly what
 * {@code POST /loans} runs. The repositories are in-memory stand-ins; validation and document checks are real.
 */
class LoanApplicationDraftServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long OWNER = 5L;
    private static final long SOMEONE_ELSE = 6L;

    /** Everything an application needs, without documents. */
    private static final String COMPLETE = """
            {
              "amount": 300.00, "tenor": 6, "ecNumber": "7654321B", "nationalIdNumber": "63-7654321-B-42",
              "mobileNumber": "+263772345678", "dateOfBirth": "1990-06-18", "firstName": "Tatenda",
              "lastName": "Ncube", "maritalStatus": "SINGLE", "placeOfBirth": "Bulawayo",
              "address": {"street": "7 Jason Moyo St", "city": "Bulawayo"},
              "employmentDetail": {"employerName": "Government of Zimbabwe", "ministry": "Health",
                "station": "Mpilo Central Hospital", "grade": "D2", "contractType": "PERMANENT",
                "employeeNumber": "7654321B", "grossSalary": 780.00, "netSalary": 560.00,
                "employmentStartDate": "2015-02-02"},
              "nextOfKin": {"firstName": "Chipo", "mobileNumber": "0772321321", "relationship": "SPOUSE",
                "address": {"street": "7 Jason Moyo St", "city": "Bulawayo"}},
              "loanPurpose": "MEDICAL", "lineOfBusiness": "EDUCATION"
            }""";

    private static ValidatorFactory validatorFactory;

    private LoanApplicationDraftRepository draftRepository;
    private LoanApplicationDraftDocumentRepository documentRepository;
    private LoanService loanService;
    private LoanApplicationDraftService service;
    private final Map<Long, LoanApplicationDraft> drafts = new HashMap<>();
    private final Map<DocumentType, LoanApplicationDraftDocument> documents = new EnumMap<>(DocumentType.class);
    private long callerId = OWNER;

    @BeforeAll
    static void startValidation() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void stopValidation() {
        validatorFactory.close();
    }

    @BeforeEach
    void setUp() {
        draftRepository = mock(LoanApplicationDraftRepository.class);
        documentRepository = mock(LoanApplicationDraftDocumentRepository.class);
        loanService = mock(LoanService.class);
        AuthService authService = mock(AuthService.class);
        when(authService.getLoggedInUser()).thenAnswer(invocation -> {
            User user = new User();
            user.setId(callerId);
            return user;
        });
        // One draft's rows, kept in memory: id 7 and its documents.
        when(draftRepository.save(any())).thenAnswer(invocation -> {
            LoanApplicationDraft draft = invocation.getArgument(0);
            if (draft.getId() == null) {
                draft.setId(7L);
            }
            drafts.put(draft.getId(), draft);
            return draft;
        });
        when(draftRepository.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(drafts.get((Long) i.getArgument(0))));
        when(draftRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(i -> Optional.ofNullable(drafts.get((Long) i.getArgument(0))));
        when(documentRepository.findByDraftIdAndDocumentType(eq(7L), any()))
                .thenAnswer(i -> Optional.ofNullable(documents.get((DocumentType) i.getArgument(1))));
        when(documentRepository.findByDraftId(7L)).thenAnswer(i -> new ArrayList<>(documents.values()));
        when(documentRepository.save(any())).thenAnswer(i -> {
            LoanApplicationDraftDocument document = i.getArgument(0);
            documents.put(document.getDocumentType(), document);
            return document;
        });
        when(documentRepository.deleteByDraftIdAndDocumentType(eq(7L), any()))
                .thenAnswer(i -> documents.remove((DocumentType) i.getArgument(1)) == null ? 0 : 1);
        when(documentRepository.findSummaries(any())).thenAnswer(i -> documents.values().stream()
                .map(d -> new DraftDocumentSummary(7L, d.getDocumentType(), d.getContentType(), d.getSizeBytes(),
                        d.getSha256(), d.getUploadedAt()))
                .toList());

        Validator validator = validatorFactory.getValidator();
        DocumentInspector inspector = new DocumentInspector(new FileSignatureValidator(), new DocumentUploadProperties());
        service = new LoanApplicationDraftService(draftRepository, documentRepository, inspector, loanService,
                authService, validator, new LoanApplicationDraftProperties());
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static String payslip() {
        return TestDocuments.base64(TestDocuments.pdf(1));
    }

    private static String signature() {
        return TestDocuments.base64(TestDocuments.encode(TestDocuments.signature(400, 160, false), "png"));
    }

    private JsonNode saved() {
        return JSON.readTree(drafts.get(7L).getApplication());
    }

    @Test
    @DisplayName("a draft started with a few fields keeps them, drops what is not an application field, and lists what is missing")
    void startWithAFewFields() {
        LoanApplicationDraftResponse draft = service.create(json("""
                {"amount": 300.00, "tenor": 6, "firstName": "Tatenda", "shoeSize": 9}"""));

        assertThat(draft.id()).isEqualTo(7L);
        assertThat(draft.status()).isEqualTo(LoanApplicationDraftStatus.OPEN);
        assertThat(drafts.get(7L).getOwnerUserId()).isEqualTo(OWNER);
        assertThat(saved().path("firstName").asString()).isEqualTo("Tatenda");
        assertThat(saved().path("tenor").asInt()).isEqualTo(6);
        assertThat(saved().has("shoeSize")).isFalse();
        assertThat(draft.complete()).isFalse();
        assertThat(draft.validationErrors()).containsEntry("ecNumber", "EC number is required")
                .containsEntry("employmentDetail", "Employment detail is required")
                .doesNotContainKeys("amount", "tenor", "firstName");
        assertThat(draft.documents()).isEmpty();
    }

    @Test
    @DisplayName("a draft can be started empty")
    void startEmpty() {
        LoanApplicationDraftResponse draft = service.create(null);

        assertThat(draft.validationErrors()).containsKeys("amount", "tenor", "ecNumber", "firstName");
        assertThat(draft.complete()).isFalse();
    }

    @Test
    @DisplayName("a save changes only what it sends: a nested object merges, null clears a field, the rest is kept")
    void saveIsAMergePatch() {
        service.create(json(COMPLETE));

        LoanApplicationDraftResponse draft = service.update(7L, json("""
                {"tenor": 12, "employmentDetail": {"grade": "E1"}, "placeOfBirth": null}"""));

        assertThat(saved().path("tenor").asInt()).isEqualTo(12);
        assertThat(saved().path("employmentDetail").path("grade").asString()).isEqualTo("E1");
        assertThat(saved().path("employmentDetail").path("employerName").asString())
                .isEqualTo("Government of Zimbabwe");
        assertThat(saved().has("placeOfBirth")).isFalse();
        assertThat(saved().path("firstName").asString()).isEqualTo("Tatenda");
        assertThat(draft.validationErrors()).containsOnlyKeys("placeOfBirth");
        assertThat(draft.application()).isEqualTo(saved());
    }

    @Test
    @DisplayName("a field merely not yet valid is saved and reported; a value that cannot be its field's is refused, saving nothing")
    void invalidValues() {
        service.create(json(COMPLETE));
        String before = drafts.get(7L).getApplication();

        LoanApplicationDraftResponse draft = service.update(7L, json("{\"mobileNumber\": \"0772\"}"));
        assertThat(draft.validationErrors()).containsOnlyKeys("mobileNumber");
        assertThat(draft.complete()).isFalse();

        String partlyValid = drafts.get(7L).getApplication();
        assertThat(partlyValid).isNotEqualTo(before);
        assertThatThrownBy(() -> service.update(7L, json("{\"tenor\": 3, \"dateOfBirth\": \"18/06/1990\"}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid value for 'dateOfBirth'");
        assertThatThrownBy(() -> service.update(7L, json("{\"nextOfKin\": {\"relationship\": \"COUSIN\"}}")))
                .hasMessage("Invalid value for 'nextOfKin.relationship'");
        assertThatThrownBy(() -> service.update(7L, json(
                "{\"payslipDeductions\": [{\"beneficiary\": \"PSMAS\", \"amount\": \"forty\"}]}")))
                .hasMessage("Invalid value for 'payslipDeductions[0].amount'");
        assertThatThrownBy(() -> service.update(7L, json("[1, 2]")))
                .hasMessage("A draft is saved as a JSON object of application fields");
        assertThat(drafts.get(7L).getApplication()).isEqualTo(partlyValid);
    }

    @Test
    @DisplayName("documents are checked when saved, every problem at once, and a refused save stores nothing")
    void documentsAreCheckedWhenSaved() {
        service.create(json("{\"firstName\": \"Tatenda\"}"));
        String blank = TestDocuments.base64(TestDocuments.encode(TestDocuments.flat(400, 160, Color.WHITE, false), "png"));

        assertThatThrownBy(() -> service.update(7L, json(String.format(
                "{\"tenor\": 6, \"payslipPicture\": \"%s\", \"signature\": \"%s\"}", "bm90IGEgZG9jdW1lbnQ=", blank))))
                .isInstanceOfSatisfying(DocumentRejectedException.class, e -> assertThat(e.getProblems())
                        .extracting(p -> p.documentType() + " " + p.reason())
                        .containsExactly("PAYSLIP " + DocumentProblemReason.UNSUPPORTED_TYPE,
                                "SIGNATURE " + DocumentProblemReason.BLANK));
        assertThatThrownBy(() -> service.update(7L, json("{\"payslipPicture\": 42}")))
                .hasMessage("Invalid value for 'payslipPicture': a document is sent as base64 text");

        assertThat(documents).isEmpty();
        assertThat(saved().has("tenor")).isFalse();
    }

    @Test
    @DisplayName("a document is stored apart from the fields, replaced when sent again, and removed by null or by removing the witness")
    void documentsAreStoredReplacedAndRemoved() {
        String first = payslip();
        LoanApplicationDraftResponse draft = service.create(json(String.format("""
                {"payslipPicture": "%s", "signature": "%s", "witness": {"fullName": "Chipo Dube", "signature": "%s"}}""",
                first, signature(), signature())));

        assertThat(documents).containsOnlyKeys(DocumentType.PAYSLIP, DocumentType.SIGNATURE,
                DocumentType.WITNESS_SIGNATURE);
        assertThat(documents.get(DocumentType.PAYSLIP).getContentType()).isEqualTo("application/pdf");
        assertThat(documents.get(DocumentType.PAYSLIP).getContent()).isEqualTo(Base64.getDecoder().decode(first));
        assertThat(draft.documents()).extracting(DraftDocumentSummary::documentType).hasSize(3);
        assertThat(drafts.get(7L).getApplication()).doesNotContain(first).contains("Chipo Dube");
        assertThat(saved().path("witness").has("signature")).isFalse();

        LoanApplicationDraftDocument payslip = documents.get(DocumentType.PAYSLIP);
        String second = TestDocuments.base64(TestDocuments.pdf(2));
        service.update(7L, json(String.format("{\"payslipPicture\": \"%s\"}", second)));
        assertThat(documents.get(DocumentType.PAYSLIP)).isSameAs(payslip);
        assertThat(payslip.getContent()).isEqualTo(Base64.getDecoder().decode(second));

        service.update(7L, json("{\"signature\": null}"));
        assertThat(documents).containsOnlyKeys(DocumentType.PAYSLIP, DocumentType.WITNESS_SIGNATURE);
        service.update(7L, json("{\"witness\": null, \"payslipPicture\": \"\"}"));
        assertThat(documents).isEmpty();
        assertThat(saved().has("witness")).isFalse();
    }

    @Test
    @DisplayName("a draft is its owner's alone: to anyone else it does not exist")
    void ownerOnly() {
        service.create(json(COMPLETE));
        callerId = SOMEONE_ELSE;

        assertThatThrownBy(() -> service.get(7L)).isInstanceOf(NotFoundException.class).hasMessage("Draft 7 not found");
        assertThatThrownBy(() -> service.update(7L, json("{\"tenor\": 3}"))).hasMessage("Draft 7 not found");
        assertThatThrownBy(() -> service.document(7L, DocumentType.PAYSLIP)).hasMessage("Draft 7 not found");
        assertThatThrownBy(() -> service.discard(7L)).hasMessage("Draft 7 not found");
        assertThatThrownBy(() -> service.submit(7L)).hasMessage("Draft 7 not found");
        assertThatThrownBy(() -> service.get(8L)).hasMessage("Draft 8 not found");
        verify(loanService, never()).requestLoan(any());
        assertThat(saved().path("tenor").asInt()).isEqualTo(6);
    }

    @Test
    @DisplayName("an open draft not saved for the expiry period is gone")
    void expiredDraft() {
        service.create(json(COMPLETE));
        drafts.get(7L).setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(31));

        String expired = "Draft 7 has expired: a draft is kept for 30 days after it was last saved";
        assertThatThrownBy(() -> service.get(7L)).isInstanceOf(NotFoundException.class).hasMessage(expired);
        assertThatThrownBy(() -> service.update(7L, json("{\"tenor\": 3}"))).hasMessage(expired);
        assertThatThrownBy(() -> service.submit(7L)).hasMessage(expired);
        verify(loanService, never()).requestLoan(any());

        drafts.get(7L).setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC).minusDays(29));
        assertThat(service.get(7L).status()).isEqualTo(LoanApplicationDraftStatus.OPEN);
    }

    @Test
    @DisplayName("an incomplete draft is refused at submission with every field it still needs, and no loan is requested")
    void incompleteSubmission() {
        service.create(json("{\"amount\": 300.00, \"tenor\": 6, \"mobileNumber\": \"0772\"}"));

        assertThatThrownBy(() -> service.submit(7L))
                .isInstanceOfSatisfying(IncompleteApplicationException.class, e -> assertThat(e.getFields())
                        .containsKeys("ecNumber", "nextOfKin", "mobileNumber")
                        .doesNotContainKeys("amount", "tenor"));
        verify(loanService, never()).requestLoan(any());
        assertThat(drafts.get(7L).getStatus()).isEqualTo(LoanApplicationDraftStatus.OPEN);
    }

    @Test
    @DisplayName("a complete draft is submitted with its documents, and becomes a record of the loan it became")
    void submission() {
        String payslip = payslip();
        String witnessSignature = signature();
        service.create(json(COMPLETE));
        service.update(7L, json(String.format("{\"payslipPicture\": \"%s\", \"witness\": {\"signature\": \"%s\"}}",
                payslip, witnessSignature)));
        when(loanService.requestLoan(any())).thenReturn(new LoanApplicationResponse(43L, "000000043",
                LoanApprovalStatus.NEW));

        LoanApplicationResponse loan = service.submit(7L);

        assertThat(loan.reference()).isEqualTo("000000043");
        ArgumentCaptor<LoanApplicationRequest> sent = ArgumentCaptor.forClass(LoanApplicationRequest.class);
        verify(loanService).requestLoan(sent.capture());
        assertThat(sent.getValue().getEcNumber()).isEqualTo("7654321B");
        assertThat(sent.getValue().getAmount()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(sent.getValue().getEmploymentDetail().getGrade()).isEqualTo("D2");
        assertThat(sent.getValue().getPayslipPicture()).isEqualTo(payslip);
        assertThat(sent.getValue().getWitness().getSignature()).isEqualTo(witnessSignature);
        assertThat(sent.getValue().getSignature()).isNull();

        LoanApplicationDraft draft = drafts.get(7L);
        assertThat(draft.getStatus()).isEqualTo(LoanApplicationDraftStatus.SUBMITTED);
        assertThat(draft.getLoanId()).isEqualTo(43L);
        assertThat(draft.getSubmittedAt()).isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(5, ChronoUnit.SECONDS));
        assertThat(draft.getApplication()).isNull();
        verify(documentRepository).deleteAll(any());

        LoanApplicationDraftResponse view = service.get(7L);
        assertThat(view.loanReference()).isEqualTo("000000043");
        assertThat(view.application()).isNull();
        assertThat(view.validationErrors()).isNull();
        String submitted = "Draft 7 was already submitted as loan 000000043";
        assertThatThrownBy(() -> service.submit(7L)).isInstanceOf(ConflictException.class).hasMessage(submitted);
        assertThatThrownBy(() -> service.update(7L, json("{\"tenor\": 3}"))).hasMessage(submitted);
        assertThatThrownBy(() -> service.discard(7L)).hasMessage(submitted);
        assertThatThrownBy(() -> service.document(7L, DocumentType.PAYSLIP)).hasMessage(submitted);
    }

    @Test
    @DisplayName("a submission the loan checks refuse leaves the draft as it was, to correct and submit again")
    void refusedSubmission() {
        service.create(json(COMPLETE));
        service.update(7L, json(String.format("{\"payslipPicture\": \"%s\"}", payslip())));
        String before = drafts.get(7L).getApplication();
        when(loanService.requestLoan(any())).thenThrow(new IllegalArgumentException("Must be 18+ years"));

        assertThatThrownBy(() -> service.submit(7L)).hasMessage("Must be 18+ years");

        LoanApplicationDraft draft = drafts.get(7L);
        assertThat(draft.getStatus()).isEqualTo(LoanApplicationDraftStatus.OPEN);
        assertThat(draft.getLoanId()).isNull();
        assertThat(draft.getApplication()).isEqualTo(before);
        assertThat(documents).containsOnlyKeys(DocumentType.PAYSLIP);
        verify(documentRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("discarding deletes the draft and its documents")
    void discard() {
        service.create(json(String.format("{\"payslipPicture\": \"%s\"}", payslip())));

        service.discard(7L);

        verify(documentRepository).deleteByDraftId(7L);
        verify(draftRepository).delete(drafts.get(7L));
    }

    @Test
    @DisplayName("the list is the caller's open, unexpired drafts, last saved first, each with its documents")
    void list() {
        service.create(json("{\"firstName\": \"Tatenda\", \"lastName\": \"Ncube\", \"amount\": 300, \"tenor\": 6}"));
        service.update(7L, json(String.format("{\"payslipPicture\": \"%s\"}", payslip())));
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        Page<LoanApplicationDraft> page = new PageImpl<>(List.of(drafts.get(7L)));
        when(draftRepository.findByOwnerUserIdAndStatusAndUpdatedAtGreaterThanEqual(eq(OWNER),
                eq(LoanApplicationDraftStatus.OPEN), since.capture(), pageable.capture())).thenReturn(page);

        Page<LoanApplicationDraftSummary> summaries = service.list(PageRequest.of(1, 10));

        assertThat(since.getValue()).isCloseTo(LocalDateTime.now(ZoneOffset.UTC).minusDays(30),
                within(5, ChronoUnit.SECONDS));
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        LoanApplicationDraftSummary summary = summaries.getContent().getFirst();
        assertThat(summary.firstName()).isEqualTo("Tatenda");
        assertThat(summary.amount()).isEqualByComparingTo("300");
        assertThat(summary.documents()).containsExactly(DocumentType.PAYSLIP);
        assertThat(summary.validationErrorCount()).isEqualTo(11);
    }

    @Test
    @DisplayName("a saved document is handed back with its content; one never saved is a 404")
    void document() {
        String payslip = payslip();
        service.create(json(String.format("{\"payslipPicture\": \"%s\"}", payslip)));

        DraftDocumentContent content = service.document(7L, DocumentType.PAYSLIP);

        assertThat(content.content()).isEqualTo(payslip);
        assertThat(content.contentType()).isEqualTo("application/pdf");
        assertThat(content.toString()).doesNotContain(payslip);
        assertThatThrownBy(() -> service.document(7L, DocumentType.NATIONAL_ID))
                .isInstanceOf(NotFoundException.class).hasMessage("Draft 7 has no NATIONAL_ID saved");
    }

    @Test
    @DisplayName("expired drafts are deleted from the expiry cutoff")
    void deleteExpired() {
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        when(draftRepository.deleteOpenLastSavedBefore(cutoff.capture())).thenReturn(2);

        assertThat(service.deleteExpired()).isEqualTo(2);
        assertThat(cutoff.getValue()).isCloseTo(LocalDateTime.now(ZoneOffset.UTC).minusDays(30),
                within(5, ChronoUnit.SECONDS));
    }
}
