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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.api.DashboardStatsResponse;
import zw.co.innbucks.loans.core.dashboard.DashboardService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Dashboard", description = "The platform at a glance.")
@RestController
@RequestMapping(ApiPaths.BASE + "/dashboard")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "Dashboard",
            description = "SUPER_ADMIN only. Loan counts by SSB and disbursement status, loans awaiting a Credit"
                    + " decision, disbursed and commission totals, and entity counts, in one call.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": {
                        "totalLoans": 128,
                        "loansBySsbApprovalStatus": {
                          "NEW": 4,
                          "PROCESSING": 9,
                          "APPROVED": 101,
                          "REJECTED": 14
                        },
                        "pendingCreditApprovals": 1,
                        "loansByDisbursementStatus": {
                          "PENDING": 21,
                          "SUCCESS": 96,
                          "FAILED": 11
                        },
                        "totalDisbursedAmount": 48250.00,
                        "totalAgentCommission": 311.42,
                        "merchantCount": 6,
                        "userCount": 23,
                        "batchCount": 31
                      }
                    }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<DashboardStatsResponse> dashboard() {
        return ApiResult.ok(dashboardService.getDashboardStats());
    }
}
