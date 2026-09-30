package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.employment.EmploymentEventResponse;
import zw.co.innbucks.loans.core.employment.EmploymentEventService;
import zw.co.innbucks.loans.core.employment.LoanEmploymentEventResponse;
import zw.co.innbucks.loans.core.employment.RecordEmploymentEventRequest;
import zw.co.innbucks.loans.core.employment.ResolveLoanEmploymentEventRequest;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Employment events", description = "What happened to a borrower's employment (FR-SSB-024): transfer between"
        + " ministries, secondment, promotion or notch change, suspension, unpaid leave, resignation, retirement or death"
        + " in service. An event is recorded against the borrower's EC number, and the treatment configured for its type"
        + " (Employment event treatments) is applied at once to every open application and loan under that EC number:"
        + " an application not yet paid out carries on, is HELD from SSB lodgement, credit approval and booking until an"
        + " officer releases or declines it, or is DECLINED as a credit rejection with reason REJECT_EMPLOYMENT; a loan"
        + " already paid out is left alone or opened for REVIEW. Held applications and loans under review wait in the"
        + " officers' queue.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class EmploymentEventController {

    private final EmploymentEventService employmentEventService;
    private final LoanReadScopeResolver loanReadScopeResolver;

    @Operation(summary = "Record an employment event",
            description = "Applies the event type's treatment to the borrower's open applications and loans, and returns"
                    + " what it did to each. A TRANSFER or SECONDMENT names the ministry moved to, a PROMOTION the new"
                    + " grade; only a SECONDMENT, SUSPENSION or UNPAID_LEAVE may have an end date. A declined application"
                    + " is logged as a credit rejection, a deduction it had lodged with SSB is flagged for cancellation,"
                    + " and the applicant is sent the ordinary decline SMS unless the treatment says not to. An"
                    + " application whose booking has begun counts as paid out.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recorded, with what it did to each open loan; an empty list"
                    + " when the borrower had none",
                    content = @Content(examples = {
                            @ExampleObject(name = "Suspension: application held", value = ApiExamples.EMPLOYMENT_EVENT_5_RECORDED),
                            @ExampleObject(name = "Resignation: application declined", value = ApiExamples.EMPLOYMENT_EVENT_6_RECORDED)})),
            @ApiResponse(responseCode = "400", description = "A missing or invalid field",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "effectiveDate": "Effective date is required",
                                        "eventType": "Event type is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Invalid EC number", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "EC Number is not valid"
                                    }"""),
                            @ExampleObject(name = "Transfer without a ministry", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "A transfer names the ministry the employee moves to"
                                    }"""),
                            @ExampleObject(name = "End date on a permanent event", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Only a secondment, suspension or unpaid leave has an end date"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "The same event is already recorded; nothing was changed",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "A suspension effective 2026-10-01 is already recorded for this EC number"
                            }""")))
    })
    @PostMapping("/employment-events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
    public ApiResult<EmploymentEventResponse> record(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.EMPLOYMENT_EVENT_5_REQUEST)))
            @Valid @RequestBody RecordEmploymentEventRequest request) {
        return new ApiResult<>("CREATED", "Employment event recorded", employmentEventService.record(request));
    }

    @Operation(summary = "List employment events",
            description = "Newest first, each with what it did to each loan; ecNumber narrows it to one borrower.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success; an empty page when there are none",
                    content = @Content(examples = @ExampleObject(ApiExamples.EMPLOYMENT_EVENT_PAGE))),
            @ApiResponse(responseCode = "400", description = "An invalid EC number",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_REQUEST",
                              "message": "EC Number is not valid"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/employment-events")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<PageResponse<EmploymentEventResponse>> list(
            @Parameter(description = "One borrower's EC number", example = "1234567A")
            @RequestParam(required = false) String ecNumber,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100; 20 when omitted", example = "20")
            @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(employmentEventService.find(ecNumber, Paging.of(page, size))));
    }

    @Operation(summary = "Get an employment event", description = "The event and what it did to each loan.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.EMPLOYMENT_EVENT_5_FOUND))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such event",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "Employment event 99 not found"
                            }""")))
    })
    @GetMapping("/employment-events/{eventId}")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<EmploymentEventResponse> get(@PathVariable Long eventId) {
        return ApiResult.ok(employmentEventService.get(eventId));
    }

    @Operation(summary = "List held applications and loans under review",
            description = "The officers' queue, oldest first: each held application (action HOLD) and each paid-out loan"
                    + " under review (action REVIEW) still OPEN, with the event and where the loan stands now.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success; an empty list when nothing is waiting",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_EMPLOYMENT_EVENT_QUEUE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/loan-employment-events")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<List<LoanEmploymentEventResponse>> queue() {
        return ApiResult.ok(employmentEventService.queue());
    }

    @Operation(summary = "Resolve a held application or a loan under review",
            description = "A held application is RELEASED to carry on (to SSB, Credit and payout as usual) or DECLINED as"
                    + " a credit rejection with reason REJECT_EMPLOYMENT, as when recorded. A loan under review is"
                    + " REVIEWED, the comment saying how it will now be repaid. Nobody may release an application they"
                    + " originated or are a party to. Each is resolved once.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resolved",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_EMPLOYMENT_EVENT_11_RELEASED))),
            @ApiResponse(responseCode = "400", description = "A missing field, or an outcome that does not fit",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "comment": "Comment is required",
                                        "outcome": "Outcome is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Outcome for a hold", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "A held application is resolved as RELEASED or DECLINED"
                                    }"""),
                            @ExampleObject(name = "Outcome for a review", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "A loan under review is resolved as REVIEWED"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN, or the caller may not"
                    + " release this application",
                    content = @Content(examples = {
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN),
                            @ExampleObject(name = "Originator", value = """
                                    {
                                      "code": "FORBIDDEN",
                                      "message": "Loan 000000042 was originated by tmoyo, who cannot also release its hold; another credit officer must"
                                    }""")})),
            @ApiResponse(responseCode = "404", description = "No such record",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "Loan employment event 99 not found"
                            }"""))),
            @ApiResponse(responseCode = "409", description = "Already resolved, or releasing an application since declined",
                    content = @Content(examples = {
                            @ExampleObject(name = "Already resolved", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan employment event 11 is already resolved (RELEASED)"
                                    }"""),
                            @ExampleObject(name = "Since declined", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan 000000042 has since been declined or failed; resolve its hold as DECLINED"
                                    }""")}))
    })
    @PostMapping("/loan-employment-events/{loanEmploymentEventId}/resolution")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
    public ApiResult<LoanEmploymentEventResponse> resolve(
            @PathVariable Long loanEmploymentEventId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.LOAN_EMPLOYMENT_EVENT_11_RESOLUTION)))
            @Valid @RequestBody ResolveLoanEmploymentEventRequest request) {
        LoanEmploymentEventResponse resolved = employmentEventService.resolve(loanEmploymentEventId, request);
        return ApiResult.ok(switch (request.getOutcome()) {
            case RELEASED -> "Hold released; the application carries on";
            case DECLINED -> "The application is declined";
            case REVIEWED -> "Review recorded";
        }, resolved);
    }

    @Operation(summary = "A loan's employment events",
            description = "What employment events did to this loan, oldest first. A loan outside the caller's scope is"
                    + " answered exactly like one that does not exist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success; an empty list for a loan no event touched",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_42_EMPLOYMENT_EVENTS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such loan, or not one the caller may read",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @GetMapping("/loans/{loanId}/employment-events")
    public ApiResult<List<LoanEmploymentEventResponse>> forLoan(JwtAuthenticationToken authentication,
                                                                 @PathVariable Long loanId) {
        return ApiResult.ok(employmentEventService.forLoan(loanId,
                loanReadScopeResolver.resolve(authentication.getToken())));
    }
}
