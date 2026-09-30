package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.document.AmendDocumentRequest;
import zw.co.innbucks.loans.core.document.DocumentAccessResponse;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.document.LoanDocumentContent;
import zw.co.innbucks.loans.core.document.LoanDocumentService;
import zw.co.innbucks.loans.core.document.LoanDocumentSummary;
import zw.co.innbucks.loans.core.loan.LoanReadScope;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Loan documents", description = "A loan's documents with their version history, and a log of every"
        + " access (FR-SSB-009). A document is never overwritten: a replacement is the next version and every earlier"
        + " one stays. Each hand-out of a document's content, and each upload, is logged with who and when; listing"
        + " versions without content is not. A loan outside the caller's scope is answered exactly like one that does"
        + " not exist. documentType is PAYSLIP, NATIONAL_ID, SIGNATURE or WITNESS_SIGNATURE.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@Slf4j
public class LoanDocumentController {

    private static final String INVALID_DOCUMENT_TYPE = """
            {
              "code": "INVALID_PARAMETER",
              "message": "Invalid value for 'documentType'"
            }""";

    private final LoanDocumentService loanDocumentService;
    private final LoanReadScopeResolver loanReadScopeResolver;

    @Operation(summary = "List a loan's document versions",
            description = "Every version of every document, without content: payslip, national ID, signature, witness"
                    + " signature, each oldest first. origin is APPLICATION for what came with the application and"
                    + " AMENDMENT for a replacement, which carries its reason. sha256 is the file's fingerprint.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success; an empty list when the loan has no documents",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_42_DOCUMENT_HISTORY))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such loan, or not one the caller may read",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @GetMapping("/loans/{loanId}/documents")
    public ApiResult<List<LoanDocumentSummary>> history(JwtAuthenticationToken authentication,
                                                        @PathVariable Long loanId) {
        return ApiResult.ok(loanDocumentService.history(loanId, readScope(authentication)));
    }

    @Operation(summary = "View a document",
            description = "The current version of the document, with its content as base64 (show it as"
                    + " data:<contentType>;base64,<content>). Logged as a view before it is returned.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_42_PAYSLIP_CURRENT))),
            @ApiResponse(responseCode = "400", description = "Not a document type",
                    content = @Content(examples = @ExampleObject(INVALID_DOCUMENT_TYPE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such loan, not one the caller may read, or no such document",
                    content = @Content(examples = {
                            @ExampleObject(name = "No loan", value = ApiExamples.LOAN_NOT_FOUND),
                            @ExampleObject(name = "No document", value = """
                                    {
                                      "code": "NOT_FOUND",
                                      "message": "Loan 42 has no NATIONAL_ID"
                                    }""")}))
    })
    @GetMapping("/loans/{loanId}/documents/{documentType}")
    public ApiResult<LoanDocumentContent> view(JwtAuthenticationToken authentication, @PathVariable Long loanId,
                                               @PathVariable DocumentType documentType) {
        return ApiResult.ok(loanDocumentService.view(loanId, documentType, null, readScope(authentication)));
    }

    @Operation(summary = "View an earlier version of a document",
            description = "One version of the document, with its content as base64, the current one or any before it."
                    + " Logged as a view before it is returned.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_42_PAYSLIP_VERSION_1))),
            @ApiResponse(responseCode = "400", description = "Not a document type, or not a version number",
                    content = @Content(examples = @ExampleObject(INVALID_DOCUMENT_TYPE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such loan, not one the caller may read, or no such version",
                    content = @Content(examples = {
                            @ExampleObject(name = "No loan", value = ApiExamples.LOAN_NOT_FOUND),
                            @ExampleObject(name = "No version", value = """
                                    {
                                      "code": "NOT_FOUND",
                                      "message": "Loan 42 has no PAYSLIP version 3"
                                    }""")}))
    })
    @GetMapping("/loans/{loanId}/documents/{documentType}/versions/{version}")
    public ApiResult<LoanDocumentContent> viewVersion(JwtAuthenticationToken authentication, @PathVariable Long loanId,
                                                      @PathVariable DocumentType documentType,
                                                      @PathVariable int version) {
        return ApiResult.ok(loanDocumentService.view(loanId, documentType, version, readScope(authentication)));
    }

    @Operation(summary = "Replace a payslip or national ID",
            description = "Uploads a new version, kept beside the earlier ones, with the reason for it. Only a PAYSLIP or"
                    + " a NATIONAL_ID, never a signature, and only while Credit has not decided the loan (or has returned"
                    + " it for more information) and SSB has not refused it. The file must be a PDF, PNG, JPEG or GIF,"
                    + " and not the current version again. A new payslip is checked for fraud like an application's: one"
                    + " already on another loan holds this loan for payslip review. Any user who can read the loan may"
                    + " replace a document (an agent, only on their own loans); whoever does cannot then approve the loan"
                    + " or clear its payslip review.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Replaced; the new version, without content",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_42_PAYSLIP_REPLACED))),
            @ApiResponse(responseCode = "400", description = "Missing fields, a signature, or not an accepted file",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "content": "Content is required",
                                        "reason": "Reason is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Signature", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "SIGNATURE cannot be replaced: it is part of the signed application"
                                    }"""),
                            @ExampleObject(name = "Not a document", value = """
                                    {
                                      "code": "INVALID_DOCUMENT",
                                      "message": "content is not a recognised document type (PDF/PNG/JPEG/GIF)"
                                    }"""),
                            @ExampleObject(name = "Not a document type", value = INVALID_DOCUMENT_TYPE)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such loan, or not one the caller may read",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "The loan's documents can no longer change, or the file is the current one",
                    content = @Content(examples = {
                            @ExampleObject(name = "Decided", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Documents of loan 000000042 can no longer be replaced (SSB status APPROVED, credit status APPROVED)"
                                    }"""),
                            @ExampleObject(name = "Same file", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "This PAYSLIP is the same file as version 2"
                                    }""")}))
    })
    @PutMapping("/loans/{loanId}/documents/{documentType}")
    public ApiResult<LoanDocumentSummary> amend(JwtAuthenticationToken authentication, @PathVariable Long loanId,
                                                @PathVariable DocumentType documentType,
                                                @Valid @RequestBody AmendDocumentRequest request) {
        log.info("Replacing {} of loan {}", documentType, loanId);
        LoanDocumentSummary saved = loanDocumentService.amend(loanId, documentType, request, readScope(authentication));
        return ApiResult.ok(String.format("%s replaced; version %d is now current", documentType, saved.version()),
                saved);
    }

    @Operation(summary = "A loan's document access log",
            description = "Every view and upload of the loan's documents, newest first: which document and version, the"
                    + " action (VIEW or UPLOAD), who and when. The log is append-only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_42_DOCUMENT_ACCESS_LOG))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @GetMapping("/loans/{loanId}/documents/access-log")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
    public ApiResult<List<DocumentAccessResponse>> accessLog(@PathVariable Long loanId) {
        return ApiResult.ok(loanDocumentService.accessLog(loanId));
    }

    private LoanReadScope readScope(JwtAuthenticationToken authentication) {
        return loanReadScopeResolver.resolve(authentication.getToken());
    }
}
