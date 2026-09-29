package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.loan.CreditDecisionService;
import zw.co.innbucks.loans.core.loan.CreditReasonCodeResponse;
import zw.co.innbucks.loans.core.loan.InternalApprovalStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Credit reason codes", description = "The reasons a credit officer can give for a decision. Every"
        + " credit decision names one, and each code belongs to one decision.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class CreditReasonCodeController {

    private final CreditDecisionService creditDecisionService;

    @Operation(summary = "List credit reason codes",
            description = "The codes that can be chosen now, by decision and then in display order. Any signed-in user"
                    + " may read them, so a loan's creditDecisionReasonCode can be shown with its description.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.CREDIT_REASON_CODES))),
            @ApiResponse(responseCode = "400", description = "decision is not a decision",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'decision'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @GetMapping("/credit-reason-codes")
    public ApiResult<List<CreditReasonCodeResponse>> list(
            @Parameter(description = "Only the codes for this decision: APPROVED, REJECTED or RETURNED")
            @RequestParam(required = false) InternalApprovalStatus decision) {
        return ApiResult.ok(creditDecisionService.reasonCodes(decision));
    }
}
