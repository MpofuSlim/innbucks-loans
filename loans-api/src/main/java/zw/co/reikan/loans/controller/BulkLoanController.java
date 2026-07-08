package zw.co.reikan.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.bulk.BulkLoanIngestionService;
import zw.co.reikan.loans.core.bulk.BulkLoanIngestionService.BulkResult;
import zw.co.reikan.loans.core.loan.LoanRequest;

import java.security.Principal;
import java.util.List;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

/**
 * Bulk (corporate/agency) loan-application ingestion. The heavy lifting —
 * chunking, virtual-thread fan-out, per-item fault isolation — lives in
 * {@link BulkLoanIngestionService}; each item flows through the existing,
 * unchanged single-application pipeline.
 */
@Tag(name = "BULK LOANS")
@RestController
@RequestMapping("/api/loans/bulk")
@RequiredArgsConstructor
@Slf4j
public class BulkLoanController {

    private final BulkLoanIngestionService ingestionService;

    @Data
    public static class BulkLoanSubmission {
        private List<LoanRequest> applications;
    }

    @Operation(summary = "BULK LOAN SUBMISSION",
            description = "Submits a batch of loan applications. Processed in isolated chunks: "
                    + "a failing application is rejected alone with its error context while the "
                    + "rest of the batch continues.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)})
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch processed; response lists per-item outcomes"),
            @ApiResponse(responseCode = "400", description = "Empty or oversized batch"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    @PostMapping
    public BulkResult submit(@RequestBody BulkLoanSubmission submission,
                             @RequestHeader(value = "X-Channel-Id", required = false)
                             String channelId,
                             Principal principal) {
        String actor = principal == null ? "anonymous" : principal.getName();
        int size = submission.getApplications() == null ? 0 : submission.getApplications().size();
        log.info("Bulk loan submission: {} applications, actor={}, channel={}", size, actor, channelId);
        return ingestionService.ingest(submission.getApplications(), actor, channelId);
    }
}
