package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.loan.FindLoansRequest;
import zw.co.reikan.loans.core.loan.LoanDto;
import zw.co.reikan.loans.core.loan.LoanService;
import zw.co.reikan.loans.core.ndasenda.FindNdasendaBatchRequest;
import zw.co.reikan.loans.core.ndasenda.FindNdasendaBatchResponse;
import zw.co.reikan.loans.core.ndasenda.NdasendaDeductionsBatchRequest;
import zw.co.reikan.loans.core.ndasenda.NdasendaLoanApprovalServiceImpl;

import java.util.List;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

@RestController
@Slf4j
@RequestMapping

@Tag(name = "BULKIT LOANS",
        description = "### Please Note:\n" +
                "1. Auth credentials and endpoint will be provided\n" +
                "2. Please contact  _support@bulkit.co.zw_ for support.\n")

public class BatchesController {

    @Autowired
    private NdasendaLoanApprovalServiceImpl ndasendaLoanApprovalService;

    @Autowired
    private LoanService loanService;


    @Operation(summary = "SEARCH LOANS",
            description = "Provided with a valid request, this endpoint returns a list of loans matching the search parameters",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Request received for processing",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = LoansWrapper.class))}),
            @ApiResponse(responseCode = "400",
                    description = "Represents an Error Caused by the Violation of a Business Rule"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/loans/search")
    public LoansWrapper findLoans(@RequestBody FindLoansRequest request) {
        log.info("Find loan request: {}", request);
        return new LoansWrapper(loanService.findLoans(request));
    }


    @Operation(summary = "GET LOAN BY ID",
            description = "Provided with a loan id, this endpoint returns a loans details",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Request received for processing",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = LoanDto.class))}),
            @ApiResponse(responseCode = "404",
                    description = "Resource not found"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @GetMapping("/loans/{id}")
    public ResponseEntity findLoans(@PathVariable Long id) {
        log.info("Find loan by id: {}", id);
        try {
            return ResponseEntity.ok(loanService.getLoan(id));
        } catch (Exception ex) {
            log.error("", ex);
            return ResponseEntity.notFound().build();
        }
    }

    @Operation(summary = "SEARCH BATCHES",
            description = "Provided with a valid request, this endpoint returns a list of batches that matches the search parameters",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Request received for processing",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = FindNdasendaBatchResponse.class))}),
            @ApiResponse(responseCode = "400",
                    description = "Represents an Error Caused by the Violation of a Business Rule"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/batches/search")
    public FindNdasendaBatchResponse findNdasendaBatches(@RequestBody FindNdasendaBatchRequest request) {
        log.info("Searching batches: {}", request);
        return FindNdasendaBatchResponse.builder().batches(ndasendaLoanApprovalService.findBatches(request)).build();
    }


    @Operation(summary = "GET BATCH BY ID",
            description = "Return the batch and the SSB approval status for the given batch id.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Request received for processing", content = {@Content(mediaType = "application/json",
                    schema = @Schema(implementation = NdasendaDeductionsBatchRequest.class))}),
            @ApiResponse(responseCode = "400",
                    description = "Represents an Error Caused by the Violation of a Business Rule"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @GetMapping("/batches/{batchId}")
    public NdasendaDeductionsBatchRequest getBatchDetails(@PathVariable String batchId) {
        final List<NdasendaDeductionsBatchRequest> responses = ndasendaLoanApprovalService.findDeductionResponsesByBatchId(batchId);
        if (CollectionUtils.isEmpty(responses)) {
            return null;
        }
        return responses.get(0);
    }

}
