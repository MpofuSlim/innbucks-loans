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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanArrearsService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.StaffLoanArrearsApiExamples;

import java.nio.charset.StandardCharsets;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff loan arrears report", description = "The daily arrears and exception report for Credit and Human"
        + " Capital (FR-SGL-045): every paid-out Staff Grocery Loan not recovered in full, with how far past its due"
        + " date it is, whether it is escalated to Credit (BRD 3.8), written off, or owed by a borrower no longer"
        + " ACTIVE. Also emailed every morning to Credit and Human Capital where the Staff Grocery Loan jobs run.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffLoanArrearsReportController {

    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')";

    private final StaffLoanArrearsService arrearsService;

    @Operation(summary = "Staff Grocery Loans not recovered in full",
            description = "CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN. As loans' records stand now: every"
                    + " paid-out loan that is past its due date and still unpaid, written off, or owed by a borrower"
                    + " no longer ACTIVE on the register (listed even before it is due). Each line has daysPastDue and"
                    + " its ageing bucket, inArrears (past the due date and graceDays, or written off), escalated"
                    + " (unpaid escalationDays or more after the due date, not written off), the employment flag, and"
                    + " the borrower's department and status on the register now; the number is masked. Most days"
                    + " past due first. totals has the counts and amounts per currency and per bucket. Until the core"
                    + " banking collection reports recoveries, a loan still DISBURSED after its due date is taken as"
                    + " unpaid. format=csv downloads the lines as staff-loan-arrears-<date>.csv.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = {
                    @Content(mediaType = "application/json", examples = {
                            @ExampleObject(name = "Three loans", value = StaffLoanArrearsApiExamples.REPORT),
                            @ExampleObject(name = "None", value = StaffLoanArrearsApiExamples.EMPTY)}),
                    @Content(mediaType = "text/csv")}),
            @ApiResponse(responseCode = "400", description = "An unknown format",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_REQUEST",
                              "message": "format must be json or csv"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/staff-loan-arrears-report")
    @PreAuthorize(READERS)
    public ResponseEntity<?> report(
            @Parameter(description = "json (the default) or csv", example = "csv")
            @RequestParam(required = false) String format) {
        if (format == null || format.equalsIgnoreCase("json")) {
            return ResponseEntity.ok(ApiResult.ok(arrearsService.report()));
        }
        if (!format.equalsIgnoreCase("csv")) {
            throw new ValidationException("format must be json or csv");
        }
        StaffLoanArrearsService.Csv csv = arrearsService.csv();
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(csv.fileName()).build().toString())
                .body(csv.content());
    }
}
