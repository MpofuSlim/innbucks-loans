package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.staff.loan.AcceptStaffLoanRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanAppliedOffer;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanHome;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanJourneyService;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanQuote;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanQuoteRequest;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanView;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.BorrowerApiExamples;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;
import zw.co.innbucks.loans.web.SigningContexts;
import zw.co.innbucks.loans.web.StaffLoanApiExamples;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Borrower: Staff Grocery Loan (SuperApp)", description = "The Staff Grocery Loan journey in the SuperApp"
        + " (FR-SGL-025 to FR-SGL-031), for the borrower session's own staff member: the tile (GET), \"Apply\" when"
        + " there is no offer, the disclosure and agreement for the amount chosen (quote), and accepting it with a"
        + " fresh PIN or biometric, and every loan they have taken (GET /loans). A loan accepted waits for"
        + " disbursement through the bank's system, which pays the merchant it was accepted for (merchantName) and"
        + " sends the voucher. When a borrower cannot borrow, the reason comes in plain words (unavailable.message,"
        + " or a 422's message): show it as it is.")
@RestController
@RequestMapping(ApiPaths.BASE + "/borrower/staff-grocery-loan")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@PreAuthorize("hasRole('BORROWER')")
public class BorrowerStaffLoanController {

    private final StaffLoanJourneyService journeyService;

    @Operation(summary = "The Staff Grocery Loan tile",
            description = "BORROWER. At most one of loan, offer and unavailable is set. loan: the loan they hold, with"
                    + " its voucher once paid out (the code and QR value are there while it can be spent). offer: an"
                    + " offer to take up (\"Accept offer\"), with the amounts it can be drawn in. unavailable: why they"
                    + " cannot borrow now. None of them, with canApply true: show \"Apply\".")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = {
                    @ExampleObject(name = "An offer to take up", value = StaffLoanApiExamples.HOME_OFFER),
                    @ExampleObject(name = "No offer: may apply", value = StaffLoanApiExamples.HOME_APPLY),
                    @ExampleObject(name = "A loan awaiting payout", value = StaffLoanApiExamples.HOME_LOAN),
                    @ExampleObject(name = "Paid out: the voucher", value = StaffLoanApiExamples.HOME_VOUCHER),
                    @ExampleObject(name = "Cannot borrow now", value = StaffLoanApiExamples.HOME_UNAVAILABLE)})),
            @ApiResponse(responseCode = "401", description = "No valid borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping
    public ApiResult<StaffLoanHome> home(@Parameter(hidden = true) JwtAuthenticationToken authentication) {
        return ApiResult.ok(journeyService.home(staffMemberId(authentication)));
    }

    @Operation(summary = "Their Staff Grocery Loans",
            description = "BORROWER. Every Staff Grocery Loan the session's own staff member has taken, newest first,"
                    + " whatever became of it (FR-SGL-030): the one the tile shows and those before it, repaid,"
                    + " cancelled before payout or written off. Each has the tile's loan shape: statusMessage says where"
                    + " it stands in words to show as they are, and voucher is set once it was paid out, with its code"
                    + " only while it can still be spent. No items: they have never borrowed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = {
                    @ExampleObject(name = "A loan awaiting payout, and one repaid", value =
                            StaffLoanApiExamples.LOAN_HISTORY),
                    @ExampleObject(name = "Never borrowed", value = StaffLoanApiExamples.LOAN_HISTORY_NONE)})),
            @ApiResponse(responseCode = "401", description = "No valid borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/loans")
    public ApiResult<PageResponse<StaffLoanView>> loans(
            @Parameter(hidden = true) JwtAuthenticationToken authentication,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20")
            @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(journeyService.loans(staffMemberId(authentication),
                Paging.of(page, size))));
    }

    @Operation(summary = "Apply",
            description = "BORROWER. \"Apply\" without an offer (FR-SGL-025): an offer made now, on the weekly run's"
                    + " terms, valid as long (201), or the offer they already hold (200). Then quote it.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "An offer made",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.APPLIED))),
            @ApiResponse(responseCode = "200", description = "The offer they already hold",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.APPLIED_HELD))),
            @ApiResponse(responseCode = "401", description = "No valid borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "422", description = "They cannot borrow now; data.reason says why",
                    content = @Content(examples = {
                            @ExampleObject(name = "Holds a loan", value = StaffLoanApiExamples.DECLINED_ACTIVE_LOAN),
                            @ExampleObject(name = "Not available now",
                                    value = StaffLoanApiExamples.DECLINED_UNAVAILABLE)}))
    })
    @PostMapping("/offers")
    public ResponseEntity<ApiResult<StaffLoanAppliedOffer>> apply(
            @Parameter(hidden = true) JwtAuthenticationToken authentication) {
        StaffLoanAppliedOffer applied = journeyService.apply(staffMemberId(authentication));
        return applied.created() ? ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(applied))
                : ResponseEntity.ok(ApiResult.ok(applied));
    }

    @Operation(summary = "The disclosure and agreement for an amount",
            description = "BORROWER. Everything to show before acceptance (FR-SGL-026) for the amount chosen from the"
                    + " offer: amount, 0% interest, total repayable, the repayment date, how it is collected, where the"
                    + " voucher can be spent, and what happens to an unspent voucher; and the agreement filled with"
                    + " them (FR-SGL-027). Show the agreement's content and let them accept it; send back its version"
                    + " and contentSha256. Changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.QUOTE))),
            @ApiResponse(responseCode = "400", description = "Not an amount the offer can be drawn in",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.AMOUNT_NOT_ALLOWED))),
            @ApiResponse(responseCode = "401", description = "No valid borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "422", description = "The offer cannot be taken up, or they cannot borrow now",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.DECLINED_OFFER))),
            @ApiResponse(responseCode = "503", description = "No agreement has been published yet",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.TERMS_UNAVAILABLE)))
    })
    @PostMapping("/quote")
    public ApiResult<StaffLoanQuote> quote(
            @Parameter(hidden = true) JwtAuthenticationToken authentication,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(StaffLoanApiExamples.QUOTE_REQUEST)))
            @Valid @RequestBody StaffLoanQuoteRequest request) {
        return ApiResult.ok(journeyService.quote(staffMemberId(authentication), request));
    }

    @Operation(summary = "Accept the loan",
            description = "BORROWER. Accepts the quote (FR-SGL-027) with a FRESH middleware assertion that the"
                    + " borrower has just entered their PIN or used biometrics (FR-SGL-028): ask for it when they tap"
                    + " Accept, never reuse the sign-in one. Send the quote's offerId, amount, agreement version and"
                    + " contentSha256, and the device in X-Device-Id. Everything is checked again; the loan is then"
                    + " made, AWAITING_DISBURSEMENT. STEP_UP_REQUIRED: ask for the PIN again and retry."
                    + " TERMS_CHANGED: quote again and show the new agreement.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The loan",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.ACCEPTED))),
            @ApiResponse(responseCode = "400", description = "A field missing or wrong, or no device",
                    content = @Content(examples = {
                            @ExampleObject(name = "No device", value = StaffLoanApiExamples.DEVICE_MISSING),
                            @ExampleObject(name = "Amount", value = StaffLoanApiExamples.AMOUNT_NOT_ALLOWED)})),
            @ApiResponse(responseCode = "401", description = "The assertion is not a fresh PIN or biometric for this"
                    + " borrower, or no valid borrower session", content = @Content(examples = {
                    @ExampleObject(name = "Confirm with PIN", value = StaffLoanApiExamples.STEP_UP_REQUIRED),
                    @ExampleObject(name = "Assertion rejected", value = BorrowerApiExamples.ASSERTION_REJECTED),
                    @ExampleObject(name = "No session", value = ApiExamples.UNAUTHORIZED)})),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "The agreement accepted is not the one in force now",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.TERMS_CHANGED))),
            @ApiResponse(responseCode = "422", description = "The offer cannot be taken up, or they cannot borrow now",
                    content = @Content(examples = {
                            @ExampleObject(name = "Offer gone", value = StaffLoanApiExamples.DECLINED_OFFER),
                            @ExampleObject(name = "Holds a loan", value = StaffLoanApiExamples.DECLINED_ACTIVE_LOAN)})),
            @ApiResponse(responseCode = "503", description = "No agreement has been published yet",
                    content = @Content(examples = @ExampleObject(StaffLoanApiExamples.TERMS_UNAVAILABLE)))
    })
    @Parameter(in = ParameterIn.HEADER, name = SigningContexts.DEVICE_ID_HEADER, required = true,
            description = "The SuperApp installation's device id, kept as evidence of the acceptance",
            example = "a1f3c9e2-7b4d-4e8a-9c21-5d6e7f8a9b0c")
    @PostMapping("/loans")
    public ResponseEntity<ApiResult<StaffLoanView>> accept(
            @Parameter(hidden = true) JwtAuthenticationToken authentication, HttpServletRequest httpRequest,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(StaffLoanApiExamples.ACCEPT_REQUEST)))
            @Valid @RequestBody AcceptStaffLoanRequest request) {
        StaffLoanView loan = journeyService.accept(staffMemberId(authentication), request,
                SigningContexts.of(httpRequest, authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(loan));
    }

    private static long staffMemberId(JwtAuthenticationToken authentication) {
        return JwtService.borrowerStaffMemberId(authentication.getToken());
    }
}
