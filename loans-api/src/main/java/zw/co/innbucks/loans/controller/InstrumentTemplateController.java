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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplateResponse;
import zw.co.innbucks.loans.core.instrument.InstrumentTemplateService;
import zw.co.innbucks.loans.core.instrument.InstrumentTerms;
import zw.co.innbucks.loans.core.instrument.InstrumentType;
import zw.co.innbucks.loans.core.instrument.PublishInstrumentTemplateRequest;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;
import java.util.Map;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Instrument templates", description = "The wording of the loan agreement and the SSB deduction authority"
        + " (FR-SSB-013), each a series of published versions. A version is never changed: new wording is published as"
        + " the next version and is in force from then on. Once an instrument has a published version, every"
        + " application signs its version in force; until then applications are taken without it. The wording takes"
        + " {{placeholders}} for the loan's terms, filled when the applicant signs. instrumentType is LOAN_AGREEMENT or"
        + " SSB_DEDUCTION_AUTHORITY.")
@RestController
@RequestMapping(ApiPaths.BASE + "/instrument-templates")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class InstrumentTemplateController {

    private static final String INVALID_INSTRUMENT_TYPE = """
            {
              "code": "INVALID_PARAMETER",
              "message": "Invalid value for 'instrumentType'"
            }""";

    private final InstrumentTemplateService templateService;

    @Operation(summary = "List the wording in force",
            description = "The version in force of each published instrument, with its wording unfilled. An instrument"
                    + " never published is not listed, and is not signed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success; an empty list when nothing is published",
                    content = @Content(examples = @ExampleObject(ApiExamples.INSTRUMENT_TEMPLATES_IN_FORCE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @GetMapping
    public ApiResult<List<InstrumentTemplateResponse>> inForce() {
        return ApiResult.ok(templateService.current());
    }

    @Operation(summary = "Publish new wording",
            description = "SUPER_ADMIN: publishes the wording as the instrument's next version, in force from now. Every"
                    + " application from then on must be signed against it, and one that accepted an earlier version is"
                    + " refused with a 409. A placeholder that is not one of the loan terms (GET"
                    + " /instrument-templates/placeholders) is refused.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Published; the new version",
                    content = @Content(examples = @ExampleObject(ApiExamples.INSTRUMENT_TEMPLATE_PUBLISHED))),
            @ApiResponse(responseCode = "400", description = "A missing field, or a placeholder that is not a loan term",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "body": "Body is required",
                                        "instrumentType": "Instrument type is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Unknown placeholder", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Unknown placeholder {{salary}}: a placeholder must be one of the loan terms listed by GET /instrument-templates/placeholders"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<InstrumentTemplateResponse> publish(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.INSTRUMENT_TEMPLATE_PUBLISH_REQUEST)))
            @Valid @RequestBody PublishInstrumentTemplateRequest request) {
        InstrumentTemplateResponse published = templateService.publish(request);
        return new ApiResult<>("CREATED", String.format("%s version %d published and in force",
                published.instrumentType(), published.version()), published);
    }

    @Operation(summary = "List the placeholders",
            description = "Every {{placeholder}} the wording may use, with what it is filled with when the applicant"
                    + " signs. Amounts are written with two decimals and no currency, which the wording supplies.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.INSTRUMENT_PLACEHOLDERS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @GetMapping("/placeholders")
    public ApiResult<Map<String, String>> placeholders() {
        return ApiResult.ok(InstrumentTerms.placeholders());
    }

    @Operation(summary = "List an instrument's versions",
            description = "Every published version of the instrument, latest (in force) first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success; an empty list when never published",
                    content = @Content(examples = @ExampleObject(ApiExamples.INSTRUMENT_TEMPLATE_VERSIONS))),
            @ApiResponse(responseCode = "400", description = "Not an instrument type",
                    content = @Content(examples = @ExampleObject(INVALID_INSTRUMENT_TYPE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @GetMapping("/{instrumentType}/versions")
    public ApiResult<List<InstrumentTemplateResponse>> versions(@PathVariable InstrumentType instrumentType) {
        return ApiResult.ok(templateService.versions(instrumentType));
    }

    @Operation(summary = "Get one version",
            description = "One published version of the instrument's wording, in force or not: the wording a loan"
                    + " signed against that version was rendered from.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.INSTRUMENT_TEMPLATE_VERSION))),
            @ApiResponse(responseCode = "400", description = "Not an instrument type, or not a version number",
                    content = @Content(examples = @ExampleObject(INVALID_INSTRUMENT_TYPE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such version",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "LOAN_AGREEMENT has no version 9"
                            }""")))
    })
    @GetMapping("/{instrumentType}/versions/{version}")
    public ApiResult<InstrumentTemplateResponse> version(@PathVariable InstrumentType instrumentType,
                                                         @PathVariable int version) {
        return ApiResult.ok(templateService.version(instrumentType, version));
    }
}
