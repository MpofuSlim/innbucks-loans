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
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.loan.LoanBatchService;
import zw.co.innbucks.loans.core.ndasenda.DeductionBatchResponse;
import zw.co.innbucks.loans.core.ndasenda.DeductionBatchStatus;
import zw.co.innbucks.loans.core.ndasenda.FindNdasendaBatchRequest;
import zw.co.innbucks.loans.core.ndasenda.NdasendaDeductionBatch;
import zw.co.innbucks.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.time.LocalDate;
import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Deduction batches", description = "The payroll-deduction batches this system lodged with SSB"
        + " through Ndasenda. Lender-side staff only: a batch lists every borrower under the lender's code.")
@RestController
@RequestMapping(ApiPaths.BASE + "/deduction-batches")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@PreAuthorize("hasAnyRole('SUPER_ADMIN','CREDIT_MANAGER','FINANCE')")
public class DeductionBatchController {

    private final NdasendaLoanApprovalServiceImpl ndasendaService;
    private final LoanBatchService loanBatchService;

    @Operation(summary = "List deduction batches",
            description = "Batches created in the period, without their deductions. Only batches this system"
                    + " submitted; Ndasenda holds others under the same deduction code.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": [
                        {
                          "id": "B-20260930-1",
                          "status": "PROCESSED",
                          "deductionCode": "7788",
                          "creationDate": "2026-09-30",
                          "recordsCount": 1,
                          "totalAmount": 208.96
                        }
                      ]
                    }"""))),
            @ApiResponse(responseCode = "400", description = "A date not in yyyy-MM-dd, or an unknown status",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'status'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN, CREDIT_MANAGER or FINANCE",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping
    public ApiResult<List<DeductionBatchResponse>> listBatches(
            @Parameter(description = "yyyy-MM-dd", example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "yyyy-MM-dd", example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) DeductionBatchStatus status) {
        FindNdasendaBatchRequest request = new FindNdasendaBatchRequest();
        request.setFromDate(fromDate);
        request.setToDate(toDate);
        request.setBatchStatus(status);
        return ApiResult.ok(ndasendaService.findBatches(request).stream().map(DeductionBatchResponse::summary).toList());
    }

    @Operation(summary = "Get a deduction batch",
            description = "The batch with SSB's answer for each deduction. EC numbers keep their last three"
                    + " characters; national IDs are not shown.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": {
                        "id": "B-20260930-1",
                        "status": "PROCESSED",
                        "deductionCode": "7788",
                        "creationDate": "2026-09-30",
                        "recordsCount": 1,
                        "totalAmount": 208.96,
                        "deductions": [
                          {
                            "id": "88213",
                            "reference": "000000042",
                            "ecNumber": "*****67A",
                            "firstName": "Rudo",
                            "lastName": "Chikwanha",
                            "type": "NEW",
                            "startDate": "2026-10-31",
                            "endDate": "2026-12-31",
                            "amount": 208.96,
                            "status": "SUCCESS"
                          }
                        ]
                      }
                    }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN, CREDIT_MANAGER or FINANCE",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "Not a batch this system submitted, or SSB has not answered it",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "Batch B-20260930-1 not found"
                            }""")))
    })
    @GetMapping("/{batchId}")
    public ApiResult<DeductionBatchResponse> getBatch(@PathVariable String batchId) {
        // Only ours: Ndasenda answers for any batch under the lender's code.
        if (!loanBatchService.existsByBatchNumber(batchId)) {
            throw new NotFoundException("Batch " + batchId + " not found");
        }
        List<NdasendaDeductionBatch> responses = ndasendaService.findDeductionResponsesByBatchId(batchId);
        if (responses == null || responses.isEmpty()) {
            throw new NotFoundException("SSB has not answered batch " + batchId + " yet");
        }
        return ApiResult.ok(DeductionBatchResponse.detail(responses.get(0)));
    }
}
