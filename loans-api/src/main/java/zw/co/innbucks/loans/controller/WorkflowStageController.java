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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.workflow.CreateCheckpointStageRequest;
import zw.co.innbucks.loans.core.workflow.UpdateWorkflowStageRequest;
import zw.co.innbucks.loans.core.workflow.WorkflowStageResponse;
import zw.co.innbucks.loans.core.workflow.WorkflowStageService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Workflow stages", description = "The stages an application waits at for a person (FR-SSB-014):"
        + " PAYSLIP_REVIEW, CREDIT_DECISION, MORE_INFORMATION (the originator answers a return), EMPLOYMENT_EVENT_REVIEW"
        + " and DEDUCTION_CANCELLATION. The stages and what moves a loan through them are fixed controls; each stage's"
        + " name, who sees (VIEW), works (WORK) and assigns (ASSIGN) its queue, its service level and escalation rule,"
        + " and whether its items are assigned (NONE, OPTIONAL, or EXCLUSIVE: only the assignee acts) are changed here"
        + " without a release. An administrator can also add checkpoint stages, which hold loans before lodgement,"
        + " before credit approval or before booking until cleared or declined. SUPER_ADMIN holds every entitlement at"
        + " every stage; AGENTS can hold none.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class WorkflowStageController {

    private final WorkflowStageService workflowStageService;

    @Operation(summary = "List the workflow stages",
            description = "In the order the pipeline reaches them: the system stages, and any checkpoints an"
                    + " administrator has added (kind CHECKPOINT), active or not.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORKFLOW_STAGES))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/workflow-stages")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<List<WorkflowStageResponse>> list() {
        return ApiResult.ok(workflowStageService.list());
    }

    @Operation(summary = "Get a workflow stage")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORKFLOW_STAGE_CREDIT_DECISION))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such stage",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORKFLOW_STAGE_NOT_FOUND)))
    })
    @GetMapping("/workflow-stages/{stage}")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<WorkflowStageResponse> get(@PathVariable String stage) {
        return ApiResult.ok(workflowStageService.get(stage));
    }

    @Operation(summary = "Add a checkpoint stage",
            description = "A stage that holds each loan it applies to at one point until someone who works it clears"
                    + " the loan (it carries on) or declines it (a credit rejection, with a reason code):"
                    + " BEFORE_LODGEMENT, the deduction is not lodged with SSB; BEFORE_CREDIT_APPROVAL, Credit cannot"
                    + " approve it (it can still reject or return it); BEFORE_BOOKING, it is not booked or paid."
                    + " minimumPrincipal and channels narrow which loans it holds. It holds loans from now, including"
                    + " those already at its point; a loan being lodged or booked at this moment is past it. Its queue"
                    + " is GET /work-queues/{code}/items and its decisions POST /loans/{loanId}/checkpoints/{code}. The"
                    + " code and hold point are fixed once created; everything else is changed with PUT, where active"
                    + " false stops it holding loans. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(examples = @ExampleObject(ApiExamples.CHECKPOINT_STAGE_CREATED))),
            @ApiResponse(responseCode = "400", description = "A missing or out-of-range field, a bad code, a role"
                    + " AGENTS, an escalation point before the target, or an unknown channel",
                    content = @Content(examples = {
                            @ExampleObject(name = "Bad code", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "code": "Code must be 3 to 40 capital letters, digits or underscores, starting with a letter"
                                      }
                                    }"""),
                            @ExampleObject(name = "Unknown channel", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Unknown channel agents; use a channel's id, or PORTAL for applications with no channel"
                                    }"""),
                            @ExampleObject(name = "Agents", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "AGENTS originate applications and cannot be given a workflow stage"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "A stage with the code already exists",
                    content = @Content(examples = @ExampleObject(ApiExamples.CHECKPOINT_STAGE_EXISTS)))
    })
    @PostMapping("/workflow-stages")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<WorkflowStageResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.CHECKPOINT_STAGE_REQUEST)))
            @Valid @RequestBody CreateCheckpointStageRequest request) {
        WorkflowStageResponse created = workflowStageService.create(request);
        return new ApiResult<>("CREATED", "Checkpoint stage created; it holds loans at " + created.holdPoint()
                + " from now", created);
    }

    @Operation(summary = "Change a workflow stage",
            description = "Replaces the stage's whole configuration. It applies at once, to items already waiting as"
                    + " well: the target is how long an item should take, whenever it arrived, and a new entitlement"
                    + " takes effect on the caller's next request. Assignments already made stand. SUPER_ADMIN may be"
                    + " listed but always holds every entitlement. displayOrder, when given, moves the stage. For a"
                    + " checkpoint, minimumPrincipal and channels are replaced too, and active false stops it holding"
                    + " loans at once (true starts it again; omitted leaves it as it is); a system stage takes none of"
                    + " the three. Audited with what it was.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORKFLOW_STAGE_UPDATED))),
            @ApiResponse(responseCode = "400", description = "A missing or out-of-range field, a role AGENTS, an"
                    + " escalation point before the target, assignment on MORE_INFORMATION, an unknown channel, or"
                    + " checkpoint settings on a system stage",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "targetHours": "Target hours is required",
                                        "workRoles": "Work roles are required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Agents", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "AGENTS originate applications and cannot be given a workflow stage"
                                    }"""),
                            @ExampleObject(name = "Escalation before the target", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Escalation hours cannot be fewer than the target hours"
                                    }"""),
                            @ExampleObject(name = "Worked by the originator", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "MORE_INFORMATION is worked by each application's originator: its assignment must be NONE, and it has no work or assign roles"
                                    }"""),
                            @ExampleObject(name = "Checkpoint settings on a system stage", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "CREDIT_DECISION is a system stage: it applies to every loan and is always active, so it takes no minimum principal, channels or deactivation"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such stage",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORKFLOW_STAGE_NOT_FOUND)))
    })
    @PutMapping("/workflow-stages/{stage}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<WorkflowStageResponse> update(
            @PathVariable String stage,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.WORKFLOW_STAGE_REQUEST)))
            @Valid @RequestBody UpdateWorkflowStageRequest request) {
        return ApiResult.ok("Workflow stage updated; it applies to items already waiting as well",
                workflowStageService.update(stage, request));
    }
}
