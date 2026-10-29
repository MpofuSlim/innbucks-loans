package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.staff.loan.CancelStaffLoanRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanDetailResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutMethod;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutNotBookedRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutPaidRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutRequests;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatus;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutSweepResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanResponse;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanStatus;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;
import zw.co.innbucks.loans.web.StaffLoanApiExamples;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff Grocery Loans", description = "Staff Grocery Loans accepted in the SuperApp, for Credit, Finance"
        + " and Human Capital: each with the terms accepted and the agreement it was accepted under, with its evidence"
        + " (device, address, how it was authenticated). How a loan is paid out was decided when it was accepted"
        + " (payoutMethod CASH; a voucher loan carries no payoutMethod). A voucher loan awaits disbursement through"
        + " the bank's system. A CASH loan is booked with"
        + " the bank as a pre-approved loan and paid into the borrower's own account (payoutDestination,"
        + " payoutAccount) as soon as it is accepted, never twice: payoutStatus says where that stands, payoutMessage"
        + " why, and Finance sends it again, asks the bank about it, or records what the bank said here. Credit can"
        + " cancel a loan until it is paid out, but never a cash payout the bank may have taken. A borrower who stops"
        + " being ACTIVE on the register has theirs cancelled at once; once it is paid out, it is flagged instead"
        + " (employmentFlag) and Human Capital and Payroll, or Credit, are emailed.")
@RestController
@RequestMapping(ApiPaths.BASE + "/staff-loans")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffLoanController {

    private static final String READERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')";
    private static final String CREDIT = "hasAnyRole('CREDIT_MANAGER','SUPER_ADMIN')";
    private static final String PAYERS = "hasAnyRole('FINANCE','SUPER_ADMIN')";
    private static final String CHECKERS = "hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')";

    private final StaffLoanService loanService;
    private final StaffLoanPayoutRequests payoutRequests;

    @Operation(summary = "List Staff Grocery Loans",
            description = "CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN. Newest first. The borrower's number"
                    + " is masked; inArrears is true once a paid-out loan is past its due date and grace, or it was"
                    + " written off. employmentFlag is set on a paid-out loan whose borrower is no longer ACTIVE on"
                    + " the register: RECOVER_FROM_TERMINAL_BENEFITS when they left (RESIGNED, TERMINATED; Human"
                    + " Capital and the Payroll mailboxes were emailed), CREDIT_TO_DECIDE when they are SUSPENDED or"
                    + " on UNPAID_LEAVE (Credit was emailed). It clears when they are ACTIVE again. flagged=true lists"
                    + " only those. arrearsOverrideId is set on a loan accepted under Credit's arrears override"
                    + " (FR-SGL-014), when the borrower owed a written-off loan. A CASH loan carries its payout:"
                    + " payoutStatus READY (to be sent), SENDING (being sent), BOOKED (the bank took it), IN_DOUBT (it"
                    + " may or may not have been booked: never sent again, settled by the bank's inquiry or a person),"
                    + " REFUSED (the bank said no: nothing was booked), PAID (in the borrower's account, the loan"
                    + " DISBURSED), FAILED (confirmed never booked, the loan CANCELLED) or WITHDRAWN (cancelled before"
                    + " anything was booked); payoutMessage says why it stands there, payoutApplyAnswer is the bank's"
                    + " answer to the booking and payoutLastAnswer its last answer (account, ID and phone numbers"
                    + " masked), payoutBankSeenAt when the bank first said it holds it, payoutNotFoundAt when its"
                    + " inquiry first said it holds nothing under the reference, payoutDeclinedAt when it first held it"
                    + " in a declined status (REJECTED, CANCELLED, FAILED), payoutCheckedAt when its inquiry last"
                    + " answered, and bankLoanAccount the bank's own loan account by its last four characters. A payout"
                    + " field with no value is left out, and a voucher loan carries none of them (not even"
                    + " payoutMethod), exactly as before. payoutStatus and payoutMethod (VOUCHER or CASH) filter the"
                    + " list.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = {
                    @ExampleObject(name = "All", value = StaffLoanApiExamples.STAFF_LOANS),
                    @ExampleObject(name = "flagged=true", value = StaffLoanApiExamples.STAFF_LOANS_FLAGGED),
                    @ExampleObject(name = "payoutStatus=REFUSED", value = StaffLoanApiExamples.STAFF_LOANS_REFUSED),
                    @ExampleObject(name = "payoutStatus=IN_DOUBT",
                            value = StaffLoanApiExamples.STAFF_LOANS_IN_DOUBT)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a reader of staff loans",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<StaffLoanResponse>> loans(
            @Parameter(description = "Only loans in this status", example = "AWAITING_DISBURSEMENT")
            @RequestParam(required = false) StaffLoanStatus status,
            @Parameter(description = "Only this employee's", example = "E1012")
            @RequestParam(required = false) String employeeNumber,
            @Parameter(description = "true: only loans flagged for a borrower no longer ACTIVE; false: only those not",
                    example = "true")
            @RequestParam(required = false) Boolean flagged,
            @Parameter(description = "Only cash loans whose payout is in this status", example = "IN_DOUBT")
            @RequestParam(required = false) StaffLoanPayoutStatus payoutStatus,
            @Parameter(description = "Only loans paid out this way: VOUCHER or CASH", example = "CASH")
            @RequestParam(required = false) StaffLoanPayoutMethod payoutMethod,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20")
            @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(loanService.loans(status, employeeNumber, flagged, payoutStatus,
                payoutMethod, Paging.of(page, size))));
    }

    @Operation(summary = "A Staff Grocery Loan",
            description = "CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN. With the agreement it was accepted"
                    + " under: the exact text shown, and the evidence of the acceptance. intact is false when either"
                    + " no longer matches the seal made at acceptance.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.STAFF_LOAN_DETAIL))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a reader of staff loans",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.NOT_FOUND_143)))
    })
    @GetMapping("/{staffLoanId}")
    @PreAuthorize(READERS)
    public ApiResult<StaffLoanDetailResponse> loan(@PathVariable Long staffLoanId) {
        return ApiResult.ok(loanService.loan(staffLoanId));
    }

    @Operation(summary = "Cancel a loan before payout",
            description = "CREDIT_MANAGER or SUPER_ADMIN. Stops a loan still awaiting disbursement, with the reason;"
                    + " nothing was paid, so nothing is owed. The borrower may apply again. A CASH loan can be stopped"
                    + " only while nothing was booked for it (payoutStatus READY, or REFUSED by the bank): its payout"
                    + " is WITHDRAWN and the borrower is told it was not paid out. One SENDING, BOOKED or IN_DOUBT is"
                    + " refused: the bank may already have paid it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.CANCELLED))),
            @ApiResponse(responseCode = "400", description = "No reason given",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "reason": "reason is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.NOT_FOUND_143))),
            @ApiResponse(responseCode = "409", description = "Paid out, already closed, or a cash payout the bank may"
                    + " have taken", content = @Content(examples = {
                    @ExampleObject(name = "Closed", value = StaffLoanApiExamples.CANCEL_CONFLICT),
                    @ExampleObject(name = "Payout in flight", value = StaffLoanApiExamples.CANCEL_CONFLICT_PAYOUT)}))
    })
    @PostMapping("/{staffLoanId}/cancel")
    @PreAuthorize(CREDIT)
    public ApiResult<StaffLoanResponse> cancel(@PathVariable Long staffLoanId,
                                               @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
                                               @Content(examples = @ExampleObject(StaffLoanApiExamples.CANCEL_REQUEST)))
                                               @Valid @RequestBody CancelStaffLoanRequest request) {
        return ApiResult.ok(loanService.cancel(staffLoanId, request.reason()));
    }

    @Operation(summary = "Pay out a cash loan now",
            description = "FINANCE or SUPER_ADMIN. Sends one CASH loan's payout to the bank: one READY (not yet sent,"
                    + " or held: payoutMessage says why), or one the bank REFUSED, which becomes READY again under the"
                    + " same reference (a refusal proves nothing was booked). Answers at once with the loan as it was"
                    + " queued, READY: the payout is sent on its own thread once this request has committed, and may"
                    + " already be SENDING or settled by the time the answer arrives; read it again (GET) for the"
                    + " outcome. A payout the bank took or may have taken (SENDING, BOOKED, IN_DOUBT) is never"
                    + " sent again: check its status instead. Nobody may send a payout of their own loan (the"
                    + " borrower, by ID or phone).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Queued; the loan as it then stood",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUT_REQUESTED))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not FINANCE or SUPER_ADMIN, or a party to the loan",
                    content = @Content(examples = {
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN),
                            @ExampleObject(name = "Their own loan", value = StaffLoanApiExamples.PAYOUT_SEND_PARTY)})),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.NOT_FOUND_245))),
            @ApiResponse(responseCode = "409", description = "Not a cash payout READY or REFUSED",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUT_SEND_CONFLICT))),
            @ApiResponse(responseCode = "503", description = "The payout queue is full: it is READY, and nothing was"
                    + " sent", content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUT_QUEUE_FULL)))
    })
    @PostMapping("/{staffLoanId}/payout")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(PAYERS)
    public ApiResult<StaffLoanResponse> payOut(@PathVariable Long staffLoanId) {
        StaffLoanResponse loan = payoutRequests.sendNow(staffLoanId);
        return new ApiResult<>("ACCEPTED", "Payout of " + loan.reference() + " queued", loan);
    }

    @Operation(summary = "Pay all ready cash loans",
            description = "FINANCE or SUPER_ADMIN. Queues one pass over every CASH loan whose payout is READY, oldest"
                    + " first, and answers at once with how many. Payouts are sent as each loan is accepted; this sends"
                    + " what a restart, a full queue or a bank that could not be reached left READY (there is no sweep"
                    + " at boot, so press it after a restart). The pass stops at the first payout that is not sent,"
                    + " refused or in doubt: every one behind it would most likely end the same way. One not sent for a"
                    + " reason of its own (a 403 to that one request, a booking that could not be built) stays READY,"
                    + " with payoutMessage saying why, and the pass goes on. The caller's own"
                    + " loans are left out (skippedOwn): someone else must send those. Calling it again is safe: a"
                    + " payout is never sent twice.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "A pass queued",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUTS_REQUESTED))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "503", description = "The payout queue is full: nothing was queued",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUTS_QUEUE_FULL)))
    })
    @PostMapping("/payouts")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(PAYERS)
    public ApiResult<StaffLoanPayoutSweepResponse> payAllReady() {
        StaffLoanPayoutSweepResponse pass = payoutRequests.payAllReady();
        return new ApiResult<>("ACCEPTED", "Paying " + pass.ready() + " ready payouts" + (pass.skippedOwn() == 0 ? ""
                : "; " + pass.skippedOwn() + " you are a party to " + (pass.skippedOwn() == 1 ? "is" : "are")
                + " left for someone else"), pass);
    }

    @Operation(summary = "Ask the bank about a cash payout now",
            description = "CREDIT_MANAGER, FINANCE or SUPER_ADMIN. What the status job does every few minutes, for one"
                    + " CASH loan, at once: asks the bank's inquiry about a payout it took (BOOKED), may have taken"
                    + " (IN_DOUBT), or was recorded as never booked within the watch window, and records the answer."
                    + " Paid (paid-statuses), and the answer names this loan's reference, amount and account where it"
                    + " carries them (one it leaves out is not checked; one that differs holds it BOOKED): PAID, the"
                    + " loan DISBURSED and the borrower told. Held in a declined status (failed-statuses: REJECTED,"
                    + " CANCELLED, FAILED): BOOKED, with payoutDeclinedAt. Held in any other status: BOOKED. Holding"
                    + " nothing under the reference: payoutNotFoundAt, never a failure by itself. A send left"
                    + " unsettled for stale-claim-minutes (SENDING, the process died mid-call) is held IN_DOUBT first,"
                    + " and never sent again. Nothing here sends a payout. Answers with the loan as it now stands.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Asked; the loan as it now stands",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUT_CHECKED))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.NOT_FOUND_245))),
            @ApiResponse(responseCode = "409", description = "Not a payout the bank's inquiry settles, or one being"
                    + " sent right now", content = @Content(examples = {
                    @ExampleObject(name = "Not one to ask about",
                            value = StaffLoanApiExamples.PAYOUT_CHECK_NOT_CHECKABLE),
                    @ExampleObject(name = "Being sent", value = StaffLoanApiExamples.PAYOUT_CHECK_SENDING)})),
            @ApiResponse(responseCode = "502", description = "The bank's inquiry did not answer, or gave nothing to"
                    + " go on: nothing changed", content = @Content(examples = @ExampleObject(
                    StaffLoanApiExamples.PAYOUT_CHECK_UNANSWERED))),
            @ApiResponse(responseCode = "503", description = "The bank answered, and what it said could not be saved"
                    + " here: nothing changed, try again", content = @Content(examples = @ExampleObject(
                    StaffLoanApiExamples.PAYOUT_CHECK_NOT_RECORDED)))
    })
    @PostMapping("/{staffLoanId}/payout/status-check")
    @PreAuthorize(CHECKERS)
    public ApiResult<StaffLoanResponse> checkPayout(@PathVariable Long staffLoanId) {
        payoutRequests.checkNow(staffLoanId);
        return ApiResult.ok(loanService.summary(staffLoanId));
    }

    @Operation(summary = "Record a cash payout as paid, on the bank's word",
            description = "FINANCE or SUPER_ADMIN. For a CASH payout the bank took (BOOKED) or may have taken"
                    + " (IN_DOUBT) whose inquiry cannot say it is paid, once the bank has confirmed it another way:"
                    + " PAID, the loan DISBURSED, and the borrower told. Give the bank's reference for the payment and"
                    + " a note of how it was confirmed (kept as its hash). Nothing is sent to the bank. Nobody may"
                    + " record a payout of their own loan.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded; the loan as it now stands",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUT_CONFIRMED_PAID))),
            @ApiResponse(responseCode = "400", description = "No bank reference, or no note",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUT_PAID_INVALID))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not FINANCE or SUPER_ADMIN, or a party to the loan",
                    content = @Content(examples = {
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN),
                            @ExampleObject(name = "Their own loan", value = StaffLoanApiExamples.PAYOUT_PAID_PARTY)})),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.NOT_FOUND_245))),
            @ApiResponse(responseCode = "409", description = "Not a cash payout BOOKED or IN_DOUBT",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUT_PAID_CONFLICT)))
    })
    @PostMapping("/{staffLoanId}/payout/paid")
    @PreAuthorize(PAYERS)
    public ApiResult<StaffLoanResponse> confirmPaid(@PathVariable Long staffLoanId,
                                                    @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
                                                    @Content(examples = @ExampleObject(
                                                            StaffLoanApiExamples.PAYOUT_PAID_REQUEST)))
                                                    @Valid @RequestBody StaffLoanPayoutPaidRequest request) {
        payoutRequests.confirmPaid(staffLoanId, request.bankReference(), request.note());
        return ApiResult.ok(loanService.summary(staffLoanId));
    }

    @Operation(summary = "Record a cash payout as never booked",
            description = "SUPER_ADMIN only. Once the bank has confirmed it never paid the loan: FAILED, the loan"
                    + " CANCELLED, and the borrower told; they may borrow again. Only for a CASH payout whose inquiry"
                    + " has said so for at least not-booked-min-age (24 hours) and still says so (its last answer,"
                    + " payoutCheckedAt, no older than twice status-check-interval: press status-check first), either"
                    + " way: IN_DOUBT, the bank has never said it holds it (no 2xx to its booking either), and its"
                    + " inquiry reports nothing under the reference (payoutNotFoundAt); or BOOKED or IN_DOUBT and held"
                    + " by the bank in a declined status (payoutDeclinedAt). Confirm with the bank first: if it did pay"
                    + " the loan, the borrower could be paid twice. The payout is still asked about for failed-watch"
                    + " (14 days), and the bank holding it after all, in any but a declined status, holds that"
                    + " borrower's further payouts. Nobody may record a payout of their own loan.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recorded; the loan as it now stands",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUT_NOT_BOOKED))),
            @ApiResponse(responseCode = "400", description = "No note",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.PAYOUT_NOT_BOOKED_INVALID))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN, or a party to the loan",
                    content = @Content(examples = {
                            @ExampleObject(name = "Role", value = ApiExamples.FORBIDDEN),
                            @ExampleObject(name = "Their own loan",
                                    value = StaffLoanApiExamples.PAYOUT_NOT_BOOKED_PARTY)})),
            @ApiResponse(responseCode = "404", description = "No such loan",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.NOT_FOUND_245))),
            @ApiResponse(responseCode = "409", description = "Not a payout that can be recorded as never booked",
                    content = @Content(examples = {
                            @ExampleObject(name = "The bank holds it",
                                    value = StaffLoanApiExamples.PAYOUT_NOT_BOOKED_SEEN),
                            @ExampleObject(name = "Too soon",
                                    value = StaffLoanApiExamples.PAYOUT_NOT_BOOKED_TOO_SOON),
                            @ExampleObject(name = "Not asked lately",
                                    value = StaffLoanApiExamples.PAYOUT_NOT_BOOKED_STALE)}))
    })
    @PostMapping("/{staffLoanId}/payout/not-booked")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<StaffLoanResponse> confirmNotBooked(@PathVariable Long staffLoanId,
                                                         @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
                                                         @Content(examples = @ExampleObject(
                                                                 StaffLoanApiExamples.PAYOUT_NOT_BOOKED_REQUEST)))
                                                         @Valid @RequestBody StaffLoanPayoutNotBookedRequest request) {
        payoutRequests.confirmNotBooked(staffLoanId, request.note());
        return ApiResult.ok(loanService.summary(staffLoanId));
    }
}
