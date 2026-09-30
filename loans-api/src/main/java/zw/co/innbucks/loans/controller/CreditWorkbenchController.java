package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.loan.CreditWorkbenchResponse;
import zw.co.innbucks.loans.core.loan.CreditWorkbenchService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Credit workbench", description = "Everything a credit officer needs to decide one application, in one call"
        + " (FR-SSB-015 / FR-PBL-026). Decisions are still made with POST /loans/{loanId}/credit-decision.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class CreditWorkbenchController {

    private final CreditWorkbenchService creditWorkbenchService;

    @Operation(summary = "Get an application's credit workbench",
            description = "loan: the application in full, with its employer, payslip deductions, the current version"
                    + " of each document (content from the documents endpoints, which log each view) and, while it waits"
                    + " on Credit, creditTurnaround. affordability: the payslip's figures against the monthly deduction"
                    + " SSB is instructed to take; outcome is NOT_ASSESSED until the SSB deduction cap and minimum"
                    + " take-home pay are configured. exposure: the applicant's other loans with InnBucks, by EC number"
                    + " or national ID; open means not declined or failed, and not paid out with its last deduction"
                    + " already past. creditAuthority: who may approve the principal (FR-PBL-028): limitsApply false while"
                    + " no approval limits are set up; otherwise the lowest level that covers it (requiredLevel, or"
                    + " aboveEveryLevel when only SUPER_ADMIN can), the caller's own level and withinYourLimit, which"
                    + " says whether the caller may approve it or should refer it. flags: what the system has raised"
                    + " (PAYSLIP_REVIEW_PENDING, a payslip finding such as PAYSLIP_REUSED_BY_ANOTHER_APPLICANT,"
                    + " DOCUMENTS_AMENDED, EMPLOYMENT_EVENT_HOLD, CHECKPOINT_PENDING, DEDUCTION_CANCELLATION_REQUIRED,"
                    + " OTHER_OPEN_LOANS, CREDIT_DECISION_OVERDUE, CREDIT_DECISION_ESCALATED, ABOVE_YOUR_APPROVAL_LIMIT,"
                    + " REFERRED); a flag is for the officer to weigh, never a refusal. employmentEvents"
                    + " and decisions: what employment events did to it and every credit action so far, oldest first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_42_CREDIT_WORKBENCH))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not entitled to see the CREDIT_DECISION stage"
                    + " (CREDIT_MANAGER and SUPER_ADMIN by default; see GET /workflow-stages)",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @GetMapping("/loans/{loanId}/credit-workbench")
    @PreAuthorize("isAuthenticated() and @workflowAccess.may(authentication, 'CREDIT_DECISION', 'VIEW')")
    public ApiResult<CreditWorkbenchResponse> workbench(@PathVariable Long loanId) {
        return ApiResult.ok(creditWorkbenchService.workbench(loanId));
    }
}
