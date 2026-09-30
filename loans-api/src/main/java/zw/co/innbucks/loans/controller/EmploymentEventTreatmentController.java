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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.employment.EmploymentEventTreatmentResponse;
import zw.co.innbucks.loans.core.employment.EmploymentEventTreatmentService;
import zw.co.innbucks.loans.core.employment.EmploymentEventType;
import zw.co.innbucks.loans.core.employment.UpdateEmploymentEventTreatmentRequest;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Employment event treatments", description = "What each type of employment event does to a borrower's"
        + " applications and loans (FR-SSB-024), changed without a release. applicationTreatment, for applications not"
        + " yet paid out: CONTINUE, HOLD (kept from SSB lodgement, credit approval and booking until an officer releases"
        + " or declines it) or DECLINE. loanTreatment, for loans already paid out: NONE or REVIEW. notifyOnDecline:"
        + " whether a declined applicant is sent the decline SMS. A change applies to events recorded after it.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class EmploymentEventTreatmentController {

    private final EmploymentEventTreatmentService treatmentService;

    @Operation(summary = "List the treatments", description = "One per event type, in a fixed order.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.EMPLOYMENT_EVENT_TREATMENTS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/employment-event-treatments")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<List<EmploymentEventTreatmentResponse>> list() {
        return ApiResult.ok(treatmentService.list());
    }

    @Operation(summary = "Change a treatment",
            description = "Replaces the treatment of one event type, for events recorded from now on; what earlier events"
                    + " did to loans stands. Audited with what it was.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed",
                    content = @Content(examples = @ExampleObject(ApiExamples.EMPLOYMENT_EVENT_TREATMENT_UPDATED))),
            @ApiResponse(responseCode = "400", description = "A missing field, or not an event type",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "applicationTreatment": "Application treatment is required",
                                        "notifyOnDecline": "Notify on decline is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Not an event type", value = """
                                    {
                                      "code": "INVALID_PARAMETER",
                                      "message": "Invalid value for 'eventType'"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @PutMapping("/employment-event-treatments/{eventType}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<EmploymentEventTreatmentResponse> update(
            @PathVariable EmploymentEventType eventType,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.EMPLOYMENT_EVENT_TREATMENT_REQUEST)))
            @Valid @RequestBody UpdateEmploymentEventTreatmentRequest request) {
        return ApiResult.ok("Treatment updated; it applies to " + eventType + " events recorded from now on",
                treatmentService.update(eventType, request));
    }
}
