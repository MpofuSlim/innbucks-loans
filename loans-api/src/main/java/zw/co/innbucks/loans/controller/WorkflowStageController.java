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
        + " without a release. SUPER_ADMIN holds every entitlement at every stage; AGENTS can hold none.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class WorkflowStageController {

    private final WorkflowStageService workflowStageService;

    @Operation(summary = "List the workflow stages", description = "In the order the pipeline reaches them.")
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

    @Operation(summary = "Change a workflow stage",
            description = "Replaces the stage's whole configuration. It applies at once, to items already waiting as"
                    + " well: the target is how long an item should take, whenever it arrived, and a new entitlement"
                    + " takes effect on the caller's next request. Assignments already made stand. SUPER_ADMIN may be"
                    + " listed but always holds every entitlement. Audited with what it was.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORKFLOW_STAGE_UPDATED))),
            @ApiResponse(responseCode = "400", description = "A missing or out-of-range field, a role AGENTS, an"
                    + " escalation point before the target, or assignment on MORE_INFORMATION",
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
