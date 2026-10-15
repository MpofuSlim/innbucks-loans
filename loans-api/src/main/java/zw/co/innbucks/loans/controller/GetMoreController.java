package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.getmore.GetMoreStatusResponse;
import zw.co.innbucks.loans.core.getmore.GetMoreStatusService;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueResponse;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.GetMoreApiExamples;
import zw.co.innbucks.loans.web.PageResponse;
import zw.co.innbucks.loans.web.Paging;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "GetMore", description = "GetMore Groceries coupons as Staff Grocery Loan vouchers: a loan accepted for a"
        + " GetMore merchant is issued its voucher by GetMore's store at acceptance, spent there in parts, and its"
        + " sales and refunds read back from GetMore. GETMORE_MODE is OFF (nothing calls GetMore), LIVE (GetMore's own"
        + " store) or SIMULATED (staging's stand-in). Here: where GetMore stands on this host, and what the syncs could"
        + " not apply.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class GetMoreController {

    private static final String READERS = "hasAnyRole('SUPER_ADMIN','CREDIT_MANAGER','FINANCE')";

    private final GetMoreStatusService statusService;

    @Operation(summary = "Where GetMore stands on this host",
            description = "SUPER_ADMIN, CREDIT_MANAGER or FINANCE. The mode and its settings (the configured coupon"
                    + " prefix, GetMore's host when LIVE, how a coupon's value is held and whether it can be spent in"
                    + " parts), the Staff Grocery Loan's merchant and whether it is a GetMore store, anything that"
                    + " needs attention (warnings), voucher issuances not yet final by state, open sync issues, and the"
                    + " vouchers a sync is stopped at or holds a sale for. checkRule=true also reads the sales rule"
                    + " from GetMore: rule when it is read, ruleError (the error code and reason) when it is not, a"
                    + " 200 either way.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = {
                    @ExampleObject(name = "Staging: SIMULATED, rule checked",
                            value = GetMoreApiExamples.STATUS_SIMULATED),
                    @ExampleObject(name = "LIVE, the rule unreadable",
                            value = GetMoreApiExamples.STATUS_LIVE_RULE_UNREADABLE),
                    @ExampleObject(name = "OFF", value = GetMoreApiExamples.STATUS_OFF)})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN, CREDIT_MANAGER or FINANCE",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/getmore")
    @PreAuthorize(READERS)
    public ApiResult<GetMoreStatusResponse> status(
            @Parameter(description = "Also read the sales rule from GetMore", example = "true")
            @RequestParam(defaultValue = "false") boolean checkRule) {
        return ApiResult.ok(statusService.status(checkRule));
    }

    @Operation(summary = "GetMore sync issues",
            description = "CREDIT_MANAGER, FINANCE or SUPER_ADMIN. What a sync of a GetMore voucher could not apply"
                    + " (blocking: the voucher's ledger stops there until GetMore's records change) or applied with a"
                    + " doubt (a warning), oldest first. open=true (the default) lists those not yet cleared; a later"
                    + " complete sync that no longer sees one clears it. No code is ever shown.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SYNC_ISSUES))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/voucher-sync-issues")
    @PreAuthorize(READERS)
    public ApiResult<PageResponse<VoucherSyncIssueResponse>> syncIssues(
            @Parameter(description = "true: only those not cleared; false: every one", example = "true")
            @RequestParam(defaultValue = "true") boolean open,
            @Parameter(description = "Zero-based page", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size, 1 to 100", example = "20")
            @RequestParam(required = false) Integer size) {
        return ApiResult.ok(PageResponse.from(statusService.syncIssues(open, Paging.of(page, size))));
    }
}
