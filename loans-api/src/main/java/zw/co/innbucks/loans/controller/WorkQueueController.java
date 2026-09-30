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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.workflow.AssignWorkItemRequest;
import zw.co.innbucks.loans.core.workflow.WorkItemEventResponse;
import zw.co.innbucks.loans.core.workflow.WorkItemResponse;
import zw.co.innbucks.loans.core.workflow.WorkQueueService;
import zw.co.innbucks.loans.core.workflow.WorkQueueSummary;
import zw.co.innbucks.loans.core.workflow.WorkflowPipelineReportResponse;
import zw.co.innbucks.loans.core.workflow.WorkflowReportService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.time.LocalDate;
import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Work queues", description = "What waits at each workflow stage, against its service level, and who has it"
        + " (FR-SSB-014). Who may see a queue, act on it and assign it is the stage's configuration"
        + " (GET /workflow-stages). An item is one loan's wait at one stage: a loan that comes back to a stage starts a"
        + " fresh item with no assignee. Anyone who works a stage takes an unassigned item for themselves and releases"
        + " their own; anyone who assigns it gives items to anyone who works the stage and takes back anyone's. At an"
        + " EXCLUSIVE stage only the assignee may act on an assigned item. Items waiting past the escalation point are"
        + " escalated once: logged, audited and emailed.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class WorkQueueController {

    private final WorkQueueService workQueueService;
    private final WorkflowReportService workflowReportService;

    @Operation(summary = "List the work queues",
            description = "Every stage the caller may see, with how many items wait, are overdue and escalated, and,"
                    + " where items are assigned, how many nobody has and how many the caller has.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORK_QUEUES))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/work-queues")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<List<WorkQueueSummary>> queues() {
        return ApiResult.ok(workQueueService.summaries());
    }

    @Operation(summary = "List a stage's items",
            description = "Oldest wait first, each with its applicant, channel and originator, when it arrived, when it"
                    + " is due and escalates, how long it has waited, and who has it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORK_QUEUE_CREDIT_DECISION))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not entitled to see the stage (an unknown stage is seen"
                    + " by SUPER_ADMIN only)",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such stage",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORKFLOW_STAGE_NOT_FOUND)))
    })
    @GetMapping("/work-queues/{stage}/items")
    @PreAuthorize("isAuthenticated() and @workflowAccess.may(authentication, #stage, 'VIEW')")
    public ApiResult<List<WorkItemResponse>> items(
            @PathVariable String stage,
            @Parameter(description = "me for your own, none for nobody's, or a username; all when omitted",
                    example = "none")
            @RequestParam(required = false) String assignedTo) {
        return ApiResult.ok(workQueueService.items(stage, assignedTo));
    }

    @Operation(summary = "List my work", description = "The items the caller has, at every stage they may see.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.MY_WORK_ITEMS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/work-queues/mine")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<List<WorkItemResponse>> mine() {
        return ApiResult.ok(workQueueService.mine());
    }

    @Operation(summary = "Assign a work item",
            description = "Gives the loan's item at the stage to the assignee, or takes it for the caller when no"
                    + " assignee is given. Taking an unassigned item needs WORK at the stage; giving one to someone"
                    + " else, or taking one someone has, needs ASSIGN. The assignee must work the stage, and is never"
                    + " the loan's originator or a party to it where the stage decides the loan, nor whoever approved"
                    + " it at Credit where the stage follows that approval (the payout authorisation). Giving an item"
                    + " to whoever has it changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assigned",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORK_ITEM_ASSIGNED))),
            @ApiResponse(responseCode = "400", description = "An assignee who does not exist, does not work the stage,"
                    + " originated or is a party to the loan, or approved it where the stage follows the approval",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not entitled", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "fmoyo does not work Credit decision"
                                    }"""),
                            @ExampleObject(name = "Segregation of duties", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "tmoyo originated loan 000000042 or is a party to it, so cannot be given its Credit decision"
                                    }"""),
                            @ExampleObject(name = "Approved it", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "admin approved loan 000000061, so cannot be given its Payout authorisation"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "The caller does not work the stage, or may not give its"
                    + " items to others",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORK_ITEM_ASSIGN_FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such stage or loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "The stage's items are not assigned, the loan is not"
                    + " waiting there, or someone else has it and the caller may not take it",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not waiting", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan 000000042 is not waiting at Credit decision"
                                    }"""),
                            @ExampleObject(name = "Someone else's", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan 000000042's Credit decision is assigned to rnyathi; someone who assigns Credit decision can reassign it"
                                    }"""),
                            @ExampleObject(name = "Not assigned", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "More information items are not assigned"
                                    }""")}))
    })
    @PutMapping("/work-queues/{stage}/items/{loanId}/assignment")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<WorkItemResponse> assign(
            @PathVariable String stage, @PathVariable Long loanId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.WORK_ITEM_ASSIGN_REQUEST)))
            @Valid @RequestBody(required = false) AssignWorkItemRequest request) {
        WorkItemResponse item = workQueueService.assign(stage, loanId, request == null ? null : request.getAssignee());
        return ApiResult.ok("Assigned to " + item.assignedTo(), item);
    }

    @Operation(summary = "Release a work item",
            description = "Takes the loan's item at the stage back from whoever has it, returning it to the queue."
                    + " The assignee releases their own; anyone who assigns the stage releases anyone's. Releasing an"
                    + " item nobody has changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Released",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORK_ITEM_RELEASED))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Someone else has it and the caller does not assign the"
                    + " stage",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such stage or loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "The stage's items are not assigned, or the loan is not"
                    + " waiting there",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "Loan 000000042 is not waiting at Credit decision"
                            }""")))
    })
    @DeleteMapping("/work-queues/{stage}/items/{loanId}/assignment")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<WorkItemResponse> release(@PathVariable String stage, @PathVariable Long loanId) {
        return ApiResult.ok("Released", workQueueService.release(stage, loanId));
    }

    @Operation(summary = "Get a loan's work history",
            description = "Every assignment, reassignment, release and escalation of the loan's work items, oldest"
                    + " first; each names the wait it belongs to by when that wait began.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_42_WORK_HISTORY))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @GetMapping("/loans/{loanId}/work-history")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<List<WorkItemEventResponse>> history(@PathVariable Long loanId) {
        return ApiResult.ok(workQueueService.history(loanId));
    }

    @Operation(summary = "Workflow pipeline report",
            description = "Each stage's queue now (waiting, overdue, escalated, unassigned), and the waits that ended in"
                    + " the period timed against the stage's current target: adherence and the average, median and"
                    + " longest wait. The same by channel (PORTAL for the portal) and by originating agent. unmeasured"
                    + " counts waits with no record of when they began. fromDate and toDate are market days, inclusive;"
                    + " month to date when omitted.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.WORKFLOW_PIPELINE_REPORT))),
            @ApiResponse(responseCode = "400", description = "An inverted period",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_REQUEST",
                              "message": "fromDate must not be after toDate"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/reports/workflow-pipeline")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<WorkflowPipelineReportResponse> pipeline(
            @Parameter(example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        return ApiResult.ok(workflowReportService.pipeline(fromDate, toDate));
    }
}
