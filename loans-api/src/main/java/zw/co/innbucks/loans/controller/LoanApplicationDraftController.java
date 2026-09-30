package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.draft.DraftDocumentContent;
import zw.co.innbucks.loans.core.draft.LoanApplicationDraftResponse;
import zw.co.innbucks.loans.core.draft.LoanApplicationDraftService;
import zw.co.innbucks.loans.core.draft.LoanApplicationDraftSummary;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;
import zw.co.innbucks.loans.core.loan.LoanApplicationResponse;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;
import zw.co.innbucks.loans.web.SigningContexts;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Loan application drafts", description = "Save-and-resume (FR-SSB-002): an application saved part-way and"
        + " completed later. A draft belongs to the user who started it; anyone else's is answered exactly like one that"
        + " does not exist. It is saved with the same fields as POST /loans, a few at a time, and says what is still"
        + " missing. Submitting it runs exactly what POST /loans runs, so the loan it becomes, and the reference given to"
        + " the applicant, are issued at that first submission. An open draft not saved for 30 days (by default) is"
        + " deleted, since it holds the applicant's ID and payslip.")
@RestController
@RequestMapping(ApiPaths.BASE + "/loan-application-drafts")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class LoanApplicationDraftController {

    private static final String DRAFT_NOT_FOUND = """
            {
              "code": "NOT_FOUND",
              "message": "Draft 7 not found"
            }""";

    private static final String DRAFT_EXPIRED = """
            {
              "code": "NOT_FOUND",
              "message": "Draft 7 has expired: a draft is kept for 30 days after it was last saved"
            }""";

    private static final String DRAFT_SUBMITTED = """
            {
              "code": "CONFLICT",
              "message": "Draft 7 was already submitted as loan 000000043"
            }""";

    private static final String INVALID_VALUE = """
            {
              "code": "INVALID_REQUEST",
              "message": "Invalid value for 'dateOfBirth'"
            }""";

    private final LoanApplicationDraftService draftService;

    @Operation(summary = "Start a draft",
            description = "Saves whatever has been captured so far, possibly nothing, as a new draft of the caller's."
                    + " The body takes the fields of POST /loans, each optional here, documents included (base64). A"
                    + " value that cannot be what its field holds (a date that is not a yyyy-MM-dd date, an unknown enum"
                    + " name) is refused, as is a document that is not accepted (FR-SSB-005); a field merely missing or not"
                    + " yet valid is saved and listed in validationErrors. The answer is the draft as saved: its fields"
                    + " without the documents, the documents listed without content, and what is still needed.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Saved; the new draft",
                    content = @Content(examples = @ExampleObject(ApiExamples.DRAFT_7_STARTED))),
            @ApiResponse(responseCode = "400", description = "A value that cannot be its field's, or a document that is not accepted",
                    content = @Content(examples = {
                            @ExampleObject(name = "Invalid value", value = INVALID_VALUE),
                            @ExampleObject(name = "Documents refused", value = ApiExamples.DOCUMENTS_REFUSED)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<LoanApplicationDraftResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Any of the application's fields",
                    required = false, content = @Content(schema = @Schema(implementation = LoanApplicationRequest.class),
                    examples = @ExampleObject(ApiExamples.DRAFT_7_START_REQUEST)))
            @RequestBody(required = false) JsonNode application) {
        return new ApiResult<>("CREATED", "Draft saved", draftService.create(application));
    }

    @Operation(summary = "List the caller's drafts",
            description = "The caller's open drafts, last saved first, each with enough to recognise the applicant and"
                    + " see how far it got: validationErrorCount is how many fields are still missing or invalid, and"
                    + " documents lists what has been uploaded. Submitted and expired drafts are not listed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success; an empty page when the caller has none",
                    content = @Content(examples = @ExampleObject(ApiExamples.DRAFT_PAGE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @GetMapping
    public ApiResult<PageResponse<LoanApplicationDraftSummary>> list(
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100; 20 when omitted", example = "20")
            @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(draftService.list(Paging.of(page, size))));
    }

    @Operation(summary = "Resume a draft",
            description = "One of the caller's drafts. An open one comes back as saved, to fill the form with: its"
                    + " fields exactly as saved (the documents listed apart, without content), validationErrors with each"
                    + " field still missing or invalid, and complete once there are none. A submitted one names the loan it"
                    + " became and its reference, the applicant's application reference.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = {
                            @ExampleObject(name = "Open", value = ApiExamples.DRAFT_7_SAVED),
                            @ExampleObject(name = "Submitted", value = ApiExamples.DRAFT_7_SUBMITTED)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such draft of the caller's, or it expired",
                    content = @Content(examples = {
                            @ExampleObject(name = "No draft", value = DRAFT_NOT_FOUND),
                            @ExampleObject(name = "Expired", value = DRAFT_EXPIRED)}))
    })
    @GetMapping("/{draftId}")
    public ApiResult<LoanApplicationDraftResponse> get(@PathVariable Long draftId) {
        return ApiResult.ok(draftService.get(draftId));
    }

    @Operation(summary = "Save a draft",
            description = "Saves changes to one of the caller's open drafts as a JSON merge patch (RFC 7386): a field"
                    + " sent replaces the saved one, an object (address, employmentDetail) is merged field by field, null"
                    + " clears a field, and a field left out is kept. So each step of the form sends only what it changed."
                    + " A document is uploaded by sending its base64 and removed by sending null; one sent again replaces"
                    + " the saved one. Refused for the same reasons as starting a draft, in which case nothing is saved.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved; the draft as it now stands",
                    content = @Content(examples = @ExampleObject(ApiExamples.DRAFT_7_SAVED))),
            @ApiResponse(responseCode = "400", description = "A value that cannot be its field's, or a document that is not accepted",
                    content = @Content(examples = {
                            @ExampleObject(name = "Invalid value", value = INVALID_VALUE),
                            @ExampleObject(name = "Documents refused", value = ApiExamples.DOCUMENTS_REFUSED)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such draft of the caller's, or it expired",
                    content = @Content(examples = {
                            @ExampleObject(name = "No draft", value = DRAFT_NOT_FOUND),
                            @ExampleObject(name = "Expired", value = DRAFT_EXPIRED)})),
            @ApiResponse(responseCode = "409", description = "Already submitted",
                    content = @Content(examples = @ExampleObject(DRAFT_SUBMITTED)))
    })
    @PatchMapping(path = "/{draftId}", consumes = {"application/merge-patch+json", "application/json"})
    public ApiResult<LoanApplicationDraftResponse> update(
            @PathVariable Long draftId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "The fields that changed",
                    content = @Content(schema = @Schema(implementation = LoanApplicationRequest.class),
                    examples = @ExampleObject(ApiExamples.DRAFT_7_SAVE_REQUEST)))
            @RequestBody JsonNode changes) {
        return ApiResult.ok("Draft saved", draftService.update(draftId, changes));
    }

    @Operation(summary = "View a draft's document",
            description = "A document saved with one of the caller's open drafts, with its content as base64 (show it"
                    + " as data:<contentType>;base64,<content>). documentType is PAYSLIP, NATIONAL_ID, SIGNATURE or"
                    + " WITNESS_SIGNATURE.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.DRAFT_7_PAYSLIP))),
            @ApiResponse(responseCode = "400", description = "Not a document type",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'documentType'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such draft of the caller's, it expired, or no such document",
                    content = @Content(examples = {
                            @ExampleObject(name = "No draft", value = DRAFT_NOT_FOUND),
                            @ExampleObject(name = "No document", value = """
                                    {
                                      "code": "NOT_FOUND",
                                      "message": "Draft 7 has no NATIONAL_ID saved"
                                    }""")})),
            @ApiResponse(responseCode = "409", description = "Already submitted: the loan holds the documents now",
                    content = @Content(examples = @ExampleObject(DRAFT_SUBMITTED)))
    })
    @GetMapping("/{draftId}/documents/{documentType}")
    public ApiResult<DraftDocumentContent> document(@PathVariable Long draftId,
                                                    @PathVariable DocumentType documentType) {
        return ApiResult.ok(draftService.document(draftId, documentType));
    }

    @Operation(summary = "Discard a draft",
            description = "Deletes one of the caller's open drafts with its documents. A submitted draft is the loan's"
                    + " now and cannot be discarded.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deleted",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "OK",
                              "message": "Draft discarded"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such draft of the caller's, or it expired",
                    content = @Content(examples = {
                            @ExampleObject(name = "No draft", value = DRAFT_NOT_FOUND),
                            @ExampleObject(name = "Expired", value = DRAFT_EXPIRED)})),
            @ApiResponse(responseCode = "409", description = "Already submitted",
                    content = @Content(examples = @ExampleObject(DRAFT_SUBMITTED)))
    })
    @DeleteMapping("/{draftId}")
    public ApiResult<Void> discard(@PathVariable Long draftId) {
        draftService.discard(draftId);
        return ApiResult.ok("Draft discarded", null);
    }

    @Operation(summary = "Submit a draft",
            description = "Sends one of the caller's open drafts for approval as a loan application, through exactly what"
                    + " POST /loans runs, with the same answer: the loan and its reference, the applicant's application"
                    + " reference. A draft with fields still missing or invalid is refused with every one listed, as POST"
                    + " /loans lists them; so is one a business rule refuses (EC number format, age, amount limits, a loan"
                    + " already in flight). Refused, the draft stays as it was, to correct and submit again. Accepted, the"
                    + " draft is kept only as a record of the loan it became. Once an instrument is published this is"
                    + " where the application is signed (FR-SSB-013), as for POST /loans: the draft carries the"
                    + " versions accepted and the signature, and this request the X-Device-Id header.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sent for approval; the new loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.DRAFT_7_SUBMISSION))),
            @ApiResponse(responseCode = "400", description = "Incomplete, or refused by a business rule",
                    content = @Content(examples = {
                            @ExampleObject(name = "Incomplete", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "The application is not complete",
                                      "data": {
                                        "dateOfBirth": "Date of birth is required",
                                        "nextOfKin": "Next of kin is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Under 18", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Must be 18+ years"
                                    }"""),
                            @ExampleObject(name = "Amount out of range", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Loan amount should be between 20 and 2000"
                                    }"""),
                            @ExampleObject(name = "Unknown channel", value = ApiExamples.UNKNOWN_CHANNEL),
                            @ExampleObject(name = "Not signed", value = ApiExamples.APPLICATION_NOT_SIGNED)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such draft of the caller's, or it expired",
                    content = @Content(examples = {
                            @ExampleObject(name = "No draft", value = DRAFT_NOT_FOUND),
                            @ExampleObject(name = "Expired", value = DRAFT_EXPIRED)})),
            @ApiResponse(responseCode = "409", description = "Already submitted, the applicant has a loan in flight, or"
                    + " the wording accepted is no longer in force",
                    content = @Content(examples = {
                            @ExampleObject(name = "Already submitted", value = DRAFT_SUBMITTED),
                            @ExampleObject(name = "Wording changed", value = ApiExamples.INSTRUMENT_CHANGED),
                            @ExampleObject(name = "Loan in flight", value = """
                                    {
                                      "code": "APPLICATION_PENDING",
                                      "message": "You have a pending loan application."
                                    }""")}))
    })
    @Parameters({
            @Parameter(in = ParameterIn.HEADER, name = SigningContexts.DEVICE_ID_HEADER,
                    description = "The signing device, as the app or portal identifies it; required once an instrument"
                            + " is published", example = "a3f1c2e4-7b9d-4e21-9c55-1f0e8d6b2a77"),
            @Parameter(in = ParameterIn.HEADER, name = SigningContexts.SIGNER_AUTHENTICATION_HEADER,
                    description = "How the channel authenticated the applicant who signed, when it did", example = "SUPERAPP_PIN")
    })
    @PostMapping("/{draftId}/submission")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResult<LoanApplicationResponse> submit(@PathVariable Long draftId,
                                                     @Parameter(hidden = true) JwtAuthenticationToken authentication,
                                                     HttpServletRequest httpRequest) {
        return new ApiResult<>("CREATED", "Loan sent for approval",
                draftService.submit(draftId, SigningContexts.of(httpRequest, authentication)));
    }
}
