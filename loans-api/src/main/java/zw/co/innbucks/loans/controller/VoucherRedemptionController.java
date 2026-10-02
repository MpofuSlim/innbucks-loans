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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionRequest;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionResult;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionService;
import zw.co.innbucks.loans.core.voucher.VoucherValidationRequest;
import zw.co.innbucks.loans.core.voucher.VoucherValidationResponse;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.VoucherApiExamples;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Voucher redemption (merchant tills)", description = "A merchant's till integration (FR-SGL-036), signed in"
        + " as a MERCHANT_TILL user of that merchant, which reaches these two endpoints and nothing else. A till takes"
        + " only its own merchant's vouchers: another merchant's is answered exactly as an unknown code (404"
        + " VOUCHER_NOT_FOUND). Send the code as keyed or scanned: digits,"
        + " with or without spaces or dashes (the SuperApp's QR code holds the digits only). A code with a wrong check"
        + " digit is refused at once (INVALID_VOUCHER_CODE), so a mistyped digit never matches someone else's voucher."
        + " A redemption is safe to retry with the same reference: it is answered as the first time and nothing more"
        + " is spent. Two tills presenting the same voucher at once are served one after the other.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class VoucherRedemptionController {

    private static final String INVALID_CODE = """
            {
              "code": "INVALID_VOUCHER_CODE",
              "message": "That is not a valid voucher code - check the digits"
            }""";
    private static final String NOT_FOUND = """
            {
              "code": "VOUCHER_NOT_FOUND",
              "message": "No voucher has that code"
            }""";

    private final VoucherRedemptionService redemptionService;

    @Operation(summary = "Check a voucher",
            description = "MERCHANT_TILL. Before the sale: whether the voucher can be spent now (redeemable), what is"
                    + " left, until when, and the holder's name for the receipt. A voucher of the till's merchant is"
                    + " answered whatever its status; a malformed code, an unknown one, or another merchant's voucher"
                    + " is refused. Nothing is spent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.VALIDATION))),
            @ApiResponse(responseCode = "400", description = "Not a voucher code, or a field missing",
                    content = @Content(examples = @ExampleObject(INVALID_CODE))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not MERCHANT_TILL",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No voucher of this merchant has that code",
                    content = @Content(examples = @ExampleObject(NOT_FOUND))),
            @ApiResponse(responseCode = "503", description = "Vouchers are not configured on this server",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.UNAVAILABLE)))
    })
    @PostMapping("/voucher-validations")
    @PreAuthorize("hasRole('MERCHANT_TILL')")
    public ApiResult<VoucherValidationResponse> validate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(VoucherApiExamples.VALIDATION_REQUEST)))
            @Valid @RequestBody VoucherValidationRequest request) {
        return ApiResult.ok(redemptionService.validate(request));
    }

    @Operation(summary = "Redeem a voucher",
            description = "MERCHANT_TILL. Spends amount (major units, at most 2 decimals, in the voucher's currency)"
                    + " at the till. reference is the merchant's own transaction reference: unique per sale among its"
                    + " own sales. 201 when spent; 200 with replayed true when that reference was already redeemed for"
                    + " the same voucher and amount (nothing more spent); 409 REFERENCE_REUSED when it was used for"
                    + " anything else.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Redeemed",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.REDEEMED))),
            @ApiResponse(responseCode = "200", description = "Already redeemed under this reference",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.REDEEMED_REPLAYED))),
            @ApiResponse(responseCode = "400", description = "Not a voucher code, or a field missing or invalid",
                    content = @Content(examples = {
                            @ExampleObject(name = "Code", value = INVALID_CODE),
                            @ExampleObject(name = "Field", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "amount": "amount must have at most 2 decimal places"
                                      }
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not MERCHANT_TILL",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No voucher of this merchant has that code",
                    content = @Content(examples = @ExampleObject(NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "The voucher cannot be spent so; nothing was spent",
                    content = @Content(examples = {
                            @ExampleObject(name = "Balance", value = """
                                    {
                                      "code": "INSUFFICIENT_BALANCE",
                                      "message": "The amount is more than the USD 120.00 left on this voucher"
                                    }"""),
                            @ExampleObject(name = "Expired", value = """
                                    {
                                      "code": "VOUCHER_EXPIRED",
                                      "message": "This voucher has expired"
                                    }"""),
                            @ExampleObject(name = "Redeemed", value = """
                                    {
                                      "code": "VOUCHER_REDEEMED",
                                      "message": "This voucher has already been redeemed in full"
                                    }"""),
                            @ExampleObject(name = "Cancelled", value = """
                                    {
                                      "code": "VOUCHER_CANCELLED",
                                      "message": "This voucher has been cancelled"
                                    }"""),
                            @ExampleObject(name = "Currency", value = """
                                    {
                                      "code": "CURRENCY_MISMATCH",
                                      "message": "The voucher is in a different currency"
                                    }"""),
                            @ExampleObject(name = "Reference", value = """
                                    {
                                      "code": "REFERENCE_REUSED",
                                      "message": "That reference was already used for a different redemption"
                                    }"""),
                            @ExampleObject(name = "Partial", value = """
                                    {
                                      "code": "PARTIAL_REDEMPTION_NOT_ALLOWED",
                                      "message": "This voucher must be redeemed in full, in one purchase"
                                    }""")})),
            @ApiResponse(responseCode = "503", description = "Vouchers are not configured on this server",
                    content = @Content(examples = @ExampleObject(VoucherApiExamples.UNAVAILABLE)))
    })
    @PostMapping("/voucher-redemptions")
    @PreAuthorize("hasRole('MERCHANT_TILL')")
    public ResponseEntity<ApiResult<VoucherRedemptionResult>> redeem(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(VoucherApiExamples.REDEMPTION_REQUEST)))
            @Valid @RequestBody VoucherRedemptionRequest request) {
        VoucherRedemptionResult result = redemptionService.redeem(request);
        if (result.replayed()) {
            return ResponseEntity.ok(ApiResult.ok("Already redeemed under reference " + result.reference()
                    + "; nothing more was spent", result));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResult<>("CREATED", String.format(
                "Redeemed %s %s; %s %s left", result.currency(), result.amount().toPlainString(), result.currency(),
                result.balanceAfter().toPlainString()), result));
    }
}
