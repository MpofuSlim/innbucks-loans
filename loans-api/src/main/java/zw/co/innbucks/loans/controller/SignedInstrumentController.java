package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.groups.Default;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.instrument.InstrumentPreview;
import zw.co.innbucks.loans.core.instrument.SignedInstrumentResponse;
import zw.co.innbucks.loans.core.instrument.SignedInstrumentService;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Signed instruments", description = "Electronic signature of the loan agreement and the SSB deduction"
        + " authority (FR-SSB-013). The applicant reads each instrument filled with their terms, accepts the version in"
        + " force and signs; the application is signed when it is submitted. Each signed instrument is kept with the"
        + " exact text signed, the signature, and the evidence of the signing: when, through whose session, from which"
        + " device and address, and how the session signed in. Nothing is signed until the wording is published.")
@RestController
@RequestMapping(ApiPaths.BASE + "/loans")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class SignedInstrumentController {

    private final LoanService loanService;
    private final SignedInstrumentService signedInstrumentService;
    private final LoanReadScopeResolver loanReadScopeResolver;

    @Operation(summary = "Preview the instruments to sign",
            description = "Each published instrument filled with this application's terms, exactly as it will be signed"
                    + " if the application is submitted today: show it to the applicant, then send the version they"
                    + " accepted with the application. The body is the application; the terms (amount, amountType,"
                    + " tenor) and the applicant's identity are required, the rest fills what the wording names. Priced"
                    + " like the application, so a value outside the loan limits is refused here too. Nothing is saved."
                    + " An empty list means nothing is published, and nothing will be signed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.INSTRUMENT_PREVIEW))),
            @ApiResponse(responseCode = "400", description = "A missing or invalid term or identity field",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "amount": "Loan amount is required",
                                        "ecNumber": "EC number is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Invalid EC number", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "EC Number is not valid"
                                    }"""),
                            @ExampleObject(name = "Unknown channel", value = ApiExamples.UNKNOWN_CHANNEL)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @PostMapping("/instruments/preview")
    public ApiResult<List<InstrumentPreview>> preview(
            @Validated(Default.class) @RequestBody LoanApplicationRequest application) {
        return ApiResult.ok(loanService.previewInstruments(application));
    }

    @Operation(summary = "A loan's signed instruments",
            description = "The instruments the loan was signed with: the text signed, the fingerprints of the text and of"
                    + " the signature (the loan's SIGNATURE document), who submitted it, when, from which device and"
                    + " address, and how the session signed in. ipAddress is the address the request came from as this"
                    + " server saw it; forwardedFor is the X-Forwarded-For chain as received, kept as evidence, not"
                    + " trusted. intact is false if the record no longer matches the seal made when it was signed."
                    + " An empty list for a loan signed before the wording was published. A loan outside the caller's"
                    + " scope is answered exactly like one that does not exist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_43_SIGNED_INSTRUMENTS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such loan, or not one the caller may read",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @GetMapping("/{loanId}/signed-instruments")
    public ApiResult<List<SignedInstrumentResponse>> signedInstruments(JwtAuthenticationToken authentication,
                                                                       @PathVariable Long loanId) {
        return ApiResult.ok(signedInstrumentService.forLoan(loanId,
                loanReadScopeResolver.resolve(authentication.getToken())));
    }
}
