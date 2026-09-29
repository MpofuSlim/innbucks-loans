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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.disbursements.BookingNotBookedRequest;
import zw.co.innbucks.loans.core.disbursements.BookingResolutionService;
import zw.co.innbucks.loans.core.disbursements.HeldBookingResponse;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Held bookings", description = "Loans booked at InnBucks whose payout InnBucks has not confirmed.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@Slf4j
public class HeldBookingController {

    private final BookingResolutionService bookingResolutionService;

    @Operation(summary = "List held bookings",
            description = "SUPER_ADMIN, CREDIT_MANAGER and FINANCE: loans held as booked at InnBucks without a confirmed"
                    + " payout (bookingStatus CREATED, disbursementStatus PENDING). The inquiry job settles each one"
                    + " InnBucks reports paid. Loans InnBucks reports it holds nothing for come first"
                    + " (bookingNotFoundAt), then oldest first. bookingFailureKind AMBIGUOUS means the booking call's"
                    + " outcome was never known.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": [
                        {
                          "loanId": 61,
                          "reference": "000000061",
                          "bookingStatus": "CREATED",
                          "disbursementStatus": "PENDING",
                          "bookingFailureKind": "AMBIGUOUS",
                          "bookingNotFoundAt": "2026-10-01T07:30:00+02:00",
                          "ssbStatusChangedAt": "2026-09-30T08:05:12+02:00",
                          "disbursedAmount": 300.00,
                          "disbursementStatusMessage": "InnBucks reports no loan under reference 000000061. Confirm with InnBucks; if it never landed, resolve it as not booked"
                        }
                      ]
                    }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN, CREDIT_MANAGER or FINANCE",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/held-bookings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CREDIT_MANAGER','FINANCE')")
    public ApiResult<List<HeldBookingResponse>> listHeld() {
        return ApiResult.ok(bookingResolutionService.findHeld());
    }

    @Operation(summary = "Record a booking as not booked",
            description = "SUPER_ADMIN only. Records that InnBucks confirmed it booked no loan under this loan's"
                    + " reference. The loan's booking becomes FAILED (REFUSED), which makes it eligible for the manual"
                    + " recovery payout, and its SSB deduction is flagged for cancellation (BOOKING_FAILED). Nothing is"
                    + " sent to InnBucks. Confirm with InnBucks first: if the booking did land, a recovery payout would"
                    + " pay the loan twice.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded; the loan as it now stands",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "OK",
                              "message": "Success",
                              "data": {
                                "loanId": 61,
                                "reference": "000000061",
                                "bookingStatus": "FAILED",
                                "disbursementStatus": "FAILED",
                                "bookingFailureKind": "REFUSED",
                                "bookingNotFoundAt": "2026-10-01T07:30:00+02:00",
                                "ssbStatusChangedAt": "2026-09-30T08:05:12+02:00",
                                "disbursedAmount": 300.00,
                                "disbursementStatusMessage": "InnBucks confirmed no loan was booked (recorded by admin): ticket IB-7731",
                                "deductionCancellationStatus": "REQUIRED"
                              }
                            }"""))),
            @ApiResponse(responseCode = "400", description = "No note",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "note": "A note on how InnBucks confirmed the loan was not booked is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "Loan 61 not found"
                            }"""))),
            @ApiResponse(responseCode = "409", description = "The loan has no booking awaiting InnBucks",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "Loan 61 has no booking awaiting InnBucks (account status FAILED, disbursement status FAILED)"
                            }""")))
    })
    @PostMapping("/loans/{loanId}/booking/not-booked")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<HeldBookingResponse> recordNotBooked(@PathVariable Long loanId,
                                                          @Valid @RequestBody BookingNotBookedRequest request) {
        log.info("Recording InnBucks booking of loan {} as never landed", loanId);
        return ApiResult.ok(bookingResolutionService.confirmNotBooked(loanId, request.getNote()));
    }
}
