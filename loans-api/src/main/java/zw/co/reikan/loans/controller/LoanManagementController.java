package zw.co.reikan.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.reikan.loans.core.DisbursementRequest;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.*;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;
import static zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus.SUCCESS;

@RestController
@Slf4j
@RequestMapping("/api")

@Tag(name = "INNBUCKS LOANS",
        description = "### Please Note:\n" +
                "1. Auth credentials and endpoint will be provided\n" +
                "2. Please contact  _support@innbucks.co.zw_ for support.\n")
public class LoanManagementController {

    @Autowired
    private LoanRepository loanRepository;

    @Autowired
    private DisbursementService disbursementService;

    @Autowired
    private InternalApprovalService internalApprovalService;

    @Operation(operationId = "disburseLoan",
            summary = "DISBURSE LOANS",
            description = "Disburses a pending loan",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204",
                    description = "Request received for processing"),
            @ApiResponse(responseCode = "400",
                    description = "Represents an Error Caused by the Violation of a Business Rule"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/loans/{id}/disburse")
    public ResponseEntity findLoans(@PathVariable Long id) {
        log.info("Disbursing loan: {}", id);
        final Loan loan = loanRepository.findById(id).orElseThrow();
        if (loan.getDisbursementStatus() == SUCCESS) {
            log.info("Loan already disbursed");
            return ResponseEntity.badRequest().build();
        }
        disbursementService.processDisbursement(DisbursementRequest.builder()
                .amount(loan.getDisbursedAmount())
                .mobileNumber(loan.getMobileNumber())
                .reference(loan.getReference())
                .build(), loan);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "LOAN INTERNAL APPROVAL",
            description = "Loan internal approval",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Loan approved"),
            @ApiResponse(responseCode = "400",
                    description = "Represents an Error Caused by the Violation of a Business Rule"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/loans/{id}/approve")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','BULKIT_ADMIN')")
    public InternalApprovalResponse approve(@RequestBody InternalApprovalRequest request,
                                            @PathVariable Long id) {
        log.info("Loan internal approval request: {}", request);
        return internalApprovalService.approveLoan(request, id);
    }

}
