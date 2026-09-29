package zw.co.innbucks.loans.controller;

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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import zw.co.innbucks.loans.core.DisbursementService;
import zw.co.innbucks.loans.core.ManualDisbursementResult;
import zw.co.innbucks.loans.core.disbursements.BookingNotBookedRequest;
import zw.co.innbucks.loans.core.disbursements.BookingResolutionService;
import zw.co.innbucks.loans.core.disbursements.HeldBookingDto;
import zw.co.innbucks.loans.core.loan.*;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

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

    @Autowired
    private BookingResolutionService bookingResolutionService;

    @Operation(operationId = "disburseLoan",
            summary = "MANUAL RECOVERY PAYOUT",
            description = "Recovery only, SUPER_ADMIN only. Pays a loan through the InnBucks deposit rail when SSB and"
                    + " Credit approved it AND InnBucks definitively refused its pre-approved booking (the booking is"
                    + " what normally pays it). Every attempt for a loan carries one reference, MD-<loan reference>."
                    + " An attempt whose outcome is unknown (a timeout, a 5xx) is IN_DOUBT and blocks every further"
                    + " attempt until it has been confirmed with InnBucks.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Attempt made: outcome DISBURSED, REFUSED (nothing paid; may be tried again) or"
                            + " IN_DOUBT (confirm with InnBucks using the reference)"),
            @ApiResponse(responseCode = "403",
                    description = "Caller is not a SUPER_ADMIN"),
            @ApiResponse(responseCode = "404",
                    description = "No such loan"),
            @ApiResponse(responseCode = "409",
                    description = "The loan is not eligible for a manual payout; the error names why. Nothing was sent"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/loans/{id}/disburse")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ManualDisbursementResult disburseLoan(@PathVariable Long id) {
        log.info("Manual recovery payout requested for loan: {}", id);
        // The service refuses (409) anything the pre-approved booking may still pay, and never
        // pays twice: one stable reference per loan, written ahead of the InnBucks call.
        return disbursementService.disburse(id);
    }

    @Operation(summary = "LOAN INTERNAL APPROVAL",
            description = "Loan internal approval. An approval freezes where the loan is paid (the customer's"
                    + " wallet, or the merchant's settlement account as it stands now); later edits to the merchant"
                    + " do not move it. Whoever originated the loan cannot approve it.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Loan approved"),
            @ApiResponse(responseCode = "400",
                    description = "Represents an Error Caused by the Violation of a Business Rule, including a loan"
                            + " with nowhere to pay it (no payout type, or a merchant with no settlement account)"),
            @ApiResponse(responseCode = "403",
                    description = "Not a CREDIT_MANAGER or SUPER_ADMIN, or the caller originated this loan"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/loans/{id}/approve")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')")
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
                    description = "Caller is not SUPER_ADMIN, CREDIT_MANAGER or FINANCE"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @GetMapping("/loans/deduction-cancellations")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CREDIT_MANAGER','FINANCE')")
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
                    description = "Caller is not SUPER_ADMIN or FINANCE"),
            @ApiResponse(responseCode = "404",
                    description = "Resource not found"),
            @ApiResponse(responseCode = "409",
                    description = "The loan has no deduction cancellation pending, or it is already recorded"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PutMapping("/loans/{id}/deduction-cancellation")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','FINANCE')")
    public DeductionCancellationDto recordDeductionCancelled(@PathVariable Long id,
                                                             @Valid @RequestBody DeductionCancellationRequest request) {
        log.info("Recording deduction cancelled for loan: {}", id);
        return deductionCancellationService.markCancelledExternally(id, request.getNote());
    }

    @Operation(summary = "HELD INNBUCKS BOOKINGS",
            description = "Loans held as booked at InnBucks without a confirmed payout (account CREATED,"
                    + " disbursement PENDING): the inquiry job settles each one InnBucks reports paid. Loans InnBucks"
                    + " reports it holds no loan for come first (bookingNotFoundAt), then oldest first."
                    + " bookingFailureKind AMBIGUOUS means the booking call's outcome was never known. A loan InnBucks"
                    + " confirms it never booked is resolved with POST /api/loans/{id}/booking/confirm-not-booked.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Loans held as booked, reported-missing first",
                    content = {@Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = HeldBookingDto.class)))}),
            @ApiResponse(responseCode = "401",
                    description = "Not authenticated"),
            @ApiResponse(responseCode = "403",
                    description = "Caller is not SUPER_ADMIN, CREDIT_MANAGER or FINANCE"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @GetMapping("/loans/held-bookings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CREDIT_MANAGER','FINANCE')")
    public List<HeldBookingDto> findHeldBookings() {
        log.info("Finding loans held as booked at InnBucks");
        return bookingResolutionService.findHeld();
    }

    @Operation(summary = "CONFIRM INNBUCKS BOOKING NOT BOOKED",
            description = "SUPER_ADMIN only. Records that InnBucks confirmed it booked no loan under this loan's"
                    + " reference. The loan becomes FAILED with a REFUSED booking - eligible for the manual recovery"
                    + " payout - and its Ndasenda deduction is flagged for cancellation (BOOKING_FAILED). Nothing is"
                    + " sent to InnBucks. Confirm with InnBucks first: if the booking did land, a recovery payout"
                    + " would pay the loan twice.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Recorded; the loan as it now stands",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = HeldBookingDto.class))}),
            @ApiResponse(responseCode = "400",
                    description = "No note given"),
            @ApiResponse(responseCode = "401",
                    description = "Not authenticated"),
            @ApiResponse(responseCode = "403",
                    description = "Caller is not a SUPER_ADMIN"),
            @ApiResponse(responseCode = "404",
                    description = "No such loan"),
            @ApiResponse(responseCode = "409",
                    description = "The loan has no booking awaiting InnBucks (it is not CREATED/PENDING)"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/loans/{id}/booking/confirm-not-booked")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public HeldBookingDto confirmBookingNotBooked(@PathVariable Long id,
                                                  @Valid @RequestBody BookingNotBookedRequest request) {
        log.info("Recording InnBucks booking of loan {} as never landed", id);
        return bookingResolutionService.confirmNotBooked(id, request.getNote());
    }

}
