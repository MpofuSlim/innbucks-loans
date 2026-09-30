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
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.loan.DeductionCancellationRequest;
import zw.co.innbucks.loans.core.loan.DeductionCancellationResponse;
import zw.co.innbucks.loans.core.loan.DeductionCancellationService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Deduction cancellations", description = "Loans that will not be paid although their payroll"
        + " deduction reached SSB. Each deduction must be cancelled on Ndasenda's portal, then recorded here.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@Slf4j
public class DeductionCancellationController {

    private final DeductionCancellationService deductionCancellationService;

    @Operation(summary = "List deductions to cancel",
            description = "Whoever may see the DEDUCTION_CANCELLATION stage (CREDIT_MANAGER, FINANCE and SUPER_ADMIN"
                    + " by default): loans whose deduction was lodged but which will not be paid, oldest first."
                    + " Reasons: CREDIT_REJECTED, BOOKING_FAILED, BOOKING_IN_DOUBT,"
                    + " LODGEMENT_FAILED, ACCEPTED_AFTER_CLOSE. BOOKING_IN_DOUBT means the InnBucks booking failed"
                    + " without a definitive answer and the customer may hold the loan: confirm with InnBucks that"
                    + " nothing was booked before cancelling (each row's action says so).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": [
                        {
                          "loanId": 57,
                          "reference": "000000057",
                          "ecNumber": "*****89B",
                          "grossedMonthlyDeduction": 125.40,
                          "batchNumber": "B-20260930-1",
                          "ssbDeductionId": "88240",
                          "reason": "CREDIT_REJECTED",
                          "action": "The loan will not be paid but its payroll deduction reached Ndasenda: cancel the deduction on Ndasenda's portal",
                          "requestedAt": "2026-09-30T12:02:44+02:00",
                          "status": "REQUIRED"
                        }
                      ]
                    }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not entitled to see the DEDUCTION_CANCELLATION stage"
                    + " (CREDIT_MANAGER, FINANCE and SUPER_ADMIN by default; see GET /workflow-stages)",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/deduction-cancellations")
    @PreAuthorize("isAuthenticated() and @workflowAccess.may(authentication, 'DEDUCTION_CANCELLATION', 'VIEW')")
    public ApiResult<List<DeductionCancellationResponse>> listRequired() {
        return ApiResult.ok(deductionCancellationService.findRequired());
    }

    @Operation(summary = "Record a deduction cancelled",
            description = "Whoever may work the DEDUCTION_CANCELLATION stage (FINANCE and SUPER_ADMIN by default):"
                    + " records that the loan's deduction was cancelled on Ndasenda's own portal, with a note of how"
                    + " (ideally Ndasenda's cancellation reference). Nothing is sent to Ndasenda. If the stage is"
                    + " EXCLUSIVE and the loan's item is assigned, only its assignee may record it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": {
                        "loanId": 57,
                        "reference": "000000057",
                        "ecNumber": "*****89B",
                        "grossedMonthlyDeduction": 125.40,
                        "batchNumber": "B-20260930-1",
                        "ssbDeductionId": "88240",
                        "reason": "CREDIT_REJECTED",
                        "requestedAt": "2026-09-30T12:02:44+02:00",
                        "status": "CANCELLED_EXTERNALLY",
                        "note": "Cancelled on the Ndasenda portal, ref CXL-5521",
                        "cancelledBy": "finance1",
                        "cancelledAt": "2026-10-01T09:14:10+02:00"
                      }
                    }"""))),
            @ApiResponse(responseCode = "400", description = "No note",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "note": "A note on how the deduction was cancelled is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not entitled to work the DEDUCTION_CANCELLATION stage"
                    + " (FINANCE and SUPER_ADMIN by default; see GET /workflow-stages)",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "Loan 57 not found"
                            }"""))),
            @ApiResponse(responseCode = "409", description = "No cancellation pending, already recorded, or the stage is"
                    + " EXCLUSIVE and the loan's item is assigned to someone else",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not pending", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan 57 has no deduction cancellation pending"
                                    }"""),
                            @ExampleObject(name = "Already recorded", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan 57's deduction was already recorded as cancelled by finance2 at 2026-09-30T20:22:09+02:00"
                                    }"""),
                            @ExampleObject(name = "Assigned to someone else", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Loan 000000057's Deduction cancellation is assigned to finance2; only they can act on it until it is released or reassigned"
                                    }""")}))
    })
    @PutMapping("/loans/{loanId}/deduction-cancellation")
    @PreAuthorize("isAuthenticated() and @workflowAccess.may(authentication, 'DEDUCTION_CANCELLATION', 'WORK')")
    public ApiResult<DeductionCancellationResponse> recordCancelled(@PathVariable Long loanId,
                                                                   @Valid @RequestBody DeductionCancellationRequest request) {
        log.info("Recording deduction cancelled for loan {}", loanId);
        return ApiResult.ok(deductionCancellationService.markCancelledExternally(loanId, request.getNote()));
    }
}
