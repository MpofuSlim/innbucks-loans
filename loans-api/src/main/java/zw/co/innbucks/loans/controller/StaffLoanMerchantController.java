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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.merchant.ChangeStaffLoanMerchantRequest;
import zw.co.innbucks.loans.core.merchant.StaffLoanMerchantResponse;
import zw.co.innbucks.loans.core.merchant.StaffLoanMerchantService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Staff Grocery Loan merchant", description = "Which merchant the Staff Grocery Loan is for: the one a new"
        + " loan is accepted for and named in its agreement, paid to at disbursement, and whose tills alone can take"
        + " its voucher. It is one of the merchants (GET /merchants, where it shows staffLoanMerchant true)."
        + " Changing it applies to loans accepted from then on; a loan already accepted, and its voucher, keep the"
        + " merchant they were accepted for.")
@RestController
@RequestMapping(ApiPaths.BASE + "/staff-loan-merchant")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class StaffLoanMerchantController {

    private static final String MERCHANT = """
            {
              "code": "OK",
              "message": "Success",
              "data": {
                "merchantCode": "getmore-groceries",
                "name": "GetMore Groceries",
                "settlementAccountConfigured": true
              }
            }""";

    private final StaffLoanMerchantService service;

    @Operation(summary = "The Staff Grocery Loan's merchant",
            description = "CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN. settlementAccountConfigured is"
                    + " whether the merchant has an account to be paid into; until it does, no loan for it can be"
                    + " paid out.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(MERCHANT))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE, HUMAN_CAPITAL or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "None is set: no loan can be taken up until one is",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "No merchant is set for the Staff Grocery Loan"
                            }""")))
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','HUMAN_CAPITAL','SUPER_ADMIN')")
    public ApiResult<StaffLoanMerchantResponse> get() {
        return ApiResult.ok(service.get());
    }

    @Operation(summary = "Change the Staff Grocery Loan's merchant",
            description = "SUPER_ADMIN. From now on new loans are for this merchant. It must be paid to its own account"
                    + " (disbursementType MERCHANT_MOBILE_WALLET): a voucher loan is paid to the merchant, never to the"
                    + " borrower. Audited with the merchant it replaces. Naming the merchant already set changes"
                    + " nothing. Loans already accepted keep their merchant, and so do their vouchers, so its tills"
                    + " must stay able to redeem them until they are spent or expire.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed, or already this merchant",
                    content = @Content(examples = @ExampleObject(MERCHANT))),
            @ApiResponse(responseCode = "400", description = "merchantCode missing, or a merchant paid to the borrower",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_REQUEST",
                              "message": "Merchant innbucks-2562-4f1f-b961-c546ea7c0481 is not paid to its own \
                            account: a Staff Grocery Loan is paid to the merchant, never to the borrower, so set \
                            its disbursement type to MERCHANT_MOBILE_WALLET first"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No merchant has that code",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "Merchant pick-n-pay not found"
                            }"""))),
            @ApiResponse(responseCode = "409", description = "Another change landed at the same moment",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "The Staff Grocery Loan's merchant was changed at the same moment; read it \
                            again"
                            }""")))
    })
    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<StaffLoanMerchantResponse> change(
            JwtAuthenticationToken authentication,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = @ExampleObject("""
                    {
                      "merchantCode": "getmore-groceries"
                    }""")))
            @Valid @RequestBody ChangeStaffLoanMerchantRequest request) {
        return ApiResult.ok(service.change(request.merchantCode(), authentication.getName()));
    }
}
