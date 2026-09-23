package zw.co.reikan.loans.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.reikan.loans.core.DisbursementService;
import zw.co.reikan.loans.core.loan.*;

import java.util.List;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

@RestController
@Slf4j
@RequestMapping("/api")

@Tag(name = "INNBUCKS LOANS",
        description = "### Please Note:\n" +
                "1. Auth credentials and endpoint will be provided\n" +
                "2. Please contact  _support@innbucks.co.zw_ for support.\n")
public class LoanManagementController {

    @Autowired
    private DisbursementService disbursementService;

    @Autowired
    private InternalApprovalService internalApprovalService;

    @Autowired
    private DeductionCancellationService deductionCancellationService;

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
        // Race-safe + idempotent: the service loads the loan under a row lock,
        // so a concurrent or repeated disburse for the same loan cannot pay twice.
        boolean disbursed = disbursementService.disburse(id);
        return disbursed ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
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
    public InternalApprovalResponse approve(@Valid @RequestBody InternalApprovalRequest request,
                                            @PathVariable Long id) {
        log.info("Loan internal approval request: {}", request);
        return internalApprovalService.approveLoan(request, id);
    }

    @Operation(summary = "DEDUCTION CANCELLATIONS REQUIRED",
            description = "Loans whose Ndasenda payroll deduction was lodged but which will not be paid, oldest first."
                    + " Each must be cancelled on Ndasenda's portal and then recorded with"
                    + " PUT /api/loans/{id}/deduction-cancellation. Reasons: CREDIT_REJECTED, BOOKING_FAILED,"
                    + " BOOKING_IN_DOUBT, LODGEMENT_FAILED, ACCEPTED_AFTER_CLOSE. BOOKING_IN_DOUBT means the InnBucks"
                    + " booking failed without a definitive answer and the customer may hold the loan: confirm with"
                    + " InnBucks that no loan was booked before cancelling (each row's action and"
                    + " disbursementStatusMessage say so).",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Loans awaiting cancellation of their deduction",
                    content = {@Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = DeductionCancellationDto.class)))}),
            @ApiResponse(responseCode = "401",
                    description = "Not authenticated"),
            @ApiResponse(responseCode = "403",
                    description = "Caller is not BULKIT_ADMIN, CREDIT_MANAGER or FINANCE"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @GetMapping("/loans/deduction-cancellations")
    @PreAuthorize("hasAnyRole('BULKIT_ADMIN','CREDIT_MANAGER','FINANCE')")
    public List<DeductionCancellationDto> findDeductionCancellations() {
        log.info("Finding loans whose deduction must be cancelled");
        return deductionCancellationService.findRequired();
    }

    @Operation(summary = "RECORD DEDUCTION CANCELLED",
            description = "Records that the loan's Ndasenda deduction was cancelled on Ndasenda's own portal."
                    + " Nothing is sent to Ndasenda.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Cancellation recorded",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = DeductionCancellationDto.class))}),
            @ApiResponse(responseCode = "400",
                    description = "No note given"),
            @ApiResponse(responseCode = "401",
                    description = "Not authenticated"),
            @ApiResponse(responseCode = "403",
                    description = "Caller is not BULKIT_ADMIN or FINANCE"),
            @ApiResponse(responseCode = "404",
                    description = "Resource not found"),
            @ApiResponse(responseCode = "409",
                    description = "The loan has no deduction cancellation pending, or it is already recorded"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PutMapping("/loans/{id}/deduction-cancellation")
    @PreAuthorize("hasAnyRole('BULKIT_ADMIN','FINANCE')")
    public DeductionCancellationDto recordDeductionCancelled(@PathVariable Long id,
                                                             @Valid @RequestBody DeductionCancellationRequest request) {
        log.info("Recording deduction cancelled for loan: {}", id);
        return deductionCancellationService.markCancelledExternally(id, request.getNote());
    }

}
