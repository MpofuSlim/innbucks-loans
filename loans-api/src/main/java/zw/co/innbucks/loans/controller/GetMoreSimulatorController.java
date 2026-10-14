package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.getmore.simulator.GetMoreSimulatorActions;
import zw.co.innbucks.loans.core.getmore.simulator.SimAction;
import zw.co.innbucks.loans.core.getmore.simulator.SimActionResponse;
import zw.co.innbucks.loans.core.getmore.simulator.SimCaller;
import zw.co.innbucks.loans.core.getmore.simulator.SimCodeRequest;
import zw.co.innbucks.loans.core.getmore.simulator.SimFaultRequest;
import zw.co.innbucks.loans.core.getmore.simulator.SimFaultsResponse;
import zw.co.innbucks.loans.core.getmore.simulator.SimLookupResponse;
import zw.co.innbucks.loans.core.getmore.simulator.SimRefundRequest;
import zw.co.innbucks.loans.core.getmore.simulator.SimSaleRequest;
import zw.co.innbucks.loans.core.getmore.simulator.SimulatorApiKey;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.GetMoreApiExamples;

/**
 * STAGING ONLY: the GetMore simulator's test actions, for spending, invoicing, refunding and expiring simulated
 * vouchers as GetMore's tills would. The controller exists only where {@code GETMORE_MODE=SIMULATED}, so everywhere
 * else every path here is a plain 404 whatever is sent to it. Where it exists, its own security chain admits only the
 * holder of {@code GETMORE_SIMULATOR_API_KEY} ({@code SimulatorApiKeyFilter}), checked before the body is read; no
 * loans token reaches it. Each action is the simulated store's own transaction, then a sync of the voucher into loans
 * at once, so the borrower's next read already shows it.
 */
@Tag(name = "GetMore simulator (staging only)", description = "Staging only (GETMORE_MODE=SIMULATED): test actions"
        + " on GetMore's simulated store, standing in for GetMore's tills until the real store is connected. A sale"
        + " spends part of a Staff Grocery Loan voucher (any amount up to what is left, over any number of visits), an"
        + " invoice makes a held sale spent, a refund gives value back, and expiring a code lapses it now; each is"
        + " synced into loans at once. The store's answer is Magento's own shape (snake_case, codes masked), the"
        + " voucher is loans'. Needs the server's simulator api key in X-Api-Key; nowhere but staging has these paths."
        + " Every answer lists the simulator's assumptions, which are not Magento's.")
@RestController
@RequestMapping(ApiPaths.BASE + "/getmore-simulator")
@RequiredArgsConstructor
@SecurityRequirements
@ConditionalOnProperty(prefix = "loans.getmore", name = "mode", havingValue = "SIMULATED")
public class GetMoreSimulatorController {

    static final String API_KEY_HEADER = "X-Api-Key";
    private static final String KEY_DESCRIPTION = "The server's GetMore simulator api key (GETMORE_SIMULATOR_API_KEY)";

    private final GetMoreSimulatorActions actions;

    @Operation(summary = "Staging only (GETMORE_MODE=SIMULATED): spend part of a voucher at an outlet",
            description = "Name the voucher by loanReference or by code, never both (the code goes in the body,"
                    + " never a URL). amount is taken off the voucher; basketTotal is what the basket came to."
                    + " invoiced=false leaves the sale held (ordered, not yet spent) until it is invoiced. With a"
                    + " reference the same sale again is answered as before (200) and spends nothing more. 201 with"
                    + " the order, what the coupon has left at the store, the voucher after the sync, and the sync.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Sold", content = @Content(examples = {
                    @ExampleObject(name = "Spent", value = GetMoreApiExamples.SALE),
                    @ExampleObject(name = "Held until invoiced", value = GetMoreApiExamples.SALE_HELD)})),
            @ApiResponse(responseCode = "200", description = "The same reference again: the first sale",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SALE_REPLAYED))),
            @ApiResponse(responseCode = "400", description = "A field missing or wrong",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SALE_INVALID))),
            @ApiResponse(responseCode = "401", description = "Wrong or missing api key",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIMULATOR_KEY_REFUSED))),
            @ApiResponse(responseCode = "404", description = "No GetMore voucher for the loan (none, or a voucher of"
                    + " loans' own), or no coupon has the code", content = @Content(examples = {
                    @ExampleObject(name = "No voucher", value = GetMoreApiExamples.NO_GETMORE_VOUCHER),
                    @ExampleObject(name = "Not valid", value = GetMoreApiExamples.SIM_COUPON_NOT_FOUND)})),
            @ApiResponse(responseCode = "409", description = "The till refused, or the voucher is a LIVE GetMore"
                    + " voucher (NOT_A_SIMULATED_VOUCHER)", content = @Content(examples = {
                    @ExampleObject(name = "Too much", value = GetMoreApiExamples.SIM_INSUFFICIENT_BALANCE),
                    @ExampleObject(name = "Expired", value = GetMoreApiExamples.SIM_COUPON_EXPIRED),
                    @ExampleObject(name = "Spending in parts off",
                            value = GetMoreApiExamples.SIM_PARTIAL_SPEND_NOT_ALLOWED),
                    @ExampleObject(name = "Currency", value = GetMoreApiExamples.SIM_CURRENCY_MISMATCH),
                    @ExampleObject(name = "Reference used", value = GetMoreApiExamples.SIM_SALE_REFERENCE_REUSED),
                    @ExampleObject(name = "Not simulated", value = GetMoreApiExamples.NOT_A_SIMULATED_VOUCHER)})),
            @ApiResponse(responseCode = "413", description = "A body over 8 KiB",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIMULATOR_TOO_LARGE)))
    })
    @Parameter(in = ParameterIn.HEADER, name = API_KEY_HEADER, required = true, description = KEY_DESCRIPTION)
    @PostMapping("/sales")
    public ResponseEntity<ApiResult<SimActionResponse>> sell(
            HttpServletRequest http,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = {@ExampleObject(name = "Spend", value = GetMoreApiExamples.SALE_REQUEST),
                    @ExampleObject(name = "Hold until invoiced", value = GetMoreApiExamples.SALE_HELD_REQUEST)}))
            @Valid @RequestBody SimSaleRequest request) {
        return createdUnlessReplayed(actions.sell(request, caller(http)));
    }

    @Operation(summary = "Staging only (GETMORE_MODE=SIMULATED): invoice a held sale",
            description = "Invoices in full a sale placed with invoiced=false: what it took becomes spent, and the"
                    + " voucher is synced.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoiced",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.INVOICED))),
            @ApiResponse(responseCode = "401", description = "Wrong or missing api key",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIMULATOR_KEY_REFUSED))),
            @ApiResponse(responseCode = "404", description = "No such sale",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIM_SALE_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Nothing left to invoice",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIM_NOTHING_TO_INVOICE)))
    })
    @Parameter(in = ParameterIn.HEADER, name = API_KEY_HEADER, required = true, description = KEY_DESCRIPTION)
    @PostMapping("/sales/{orderNumber}/invoices")
    public ApiResult<SimActionResponse> invoice(
            HttpServletRequest http,
            @Parameter(description = "The sale's order number", example = "SIM000000013")
            @PathVariable String orderNumber) {
        SimAction<SimActionResponse> done = actions.invoice(orderNumber, caller(http));
        return ApiResult.ok(done.message(), done.data());
    }

    @Operation(summary = "Staging only (GETMORE_MODE=SIMULATED): refund a sale",
            description = "One credit memo against a sale, by its order number: amount (all still refundable when"
                    + " left out) is back on the voucher, which is synced. With a reference the same refund again is"
                    + " answered as before (200).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Refunded",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.REFUNDED))),
            @ApiResponse(responseCode = "400", description = "A field missing or wrong",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "orderNumber": "orderNumber is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "Wrong or missing api key",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIMULATOR_KEY_REFUSED))),
            @ApiResponse(responseCode = "404", description = "No such sale",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIM_SALE_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Nothing to refund, too much, or a reference used",
                    content = @Content(examples = {
                            @ExampleObject(name = "Nothing to refund",
                                    value = GetMoreApiExamples.SIM_NOTHING_TO_REFUND),
                            @ExampleObject(name = "Too much", value = GetMoreApiExamples.SIM_REFUND_TOO_LARGE),
                            @ExampleObject(name = "Reference used",
                                    value = GetMoreApiExamples.SIM_REFUND_REFERENCE_REUSED)}))
    })
    @Parameter(in = ParameterIn.HEADER, name = API_KEY_HEADER, required = true, description = KEY_DESCRIPTION)
    @PostMapping("/refunds")
    public ResponseEntity<ApiResult<SimActionResponse>> refund(
            HttpServletRequest http,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(GetMoreApiExamples.REFUND_REQUEST)))
            @Valid @RequestBody SimRefundRequest request) {
        return createdUnlessReplayed(actions.refund(request, caller(http)));
    }

    @Operation(summary = "Staging only (GETMORE_MODE=SIMULATED): expire a voucher now",
            description = "Lapses a simulated voucher at once, as if its last day had passed: its coupon's"
                    + " expiration date set at the store, the voucher stopped (SIMULATOR_EXPIRED) with what was left"
                    + " unspent, and its code withdrawn at the store by the sync.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Expired",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.EXPIRED))),
            @ApiResponse(responseCode = "401", description = "Wrong or missing api key",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIMULATOR_KEY_REFUSED))),
            @ApiResponse(responseCode = "404", description = "No GetMore voucher for the loan (none, or a voucher of"
                    + " loans' own) or code", content = @Content(examples = {
                    @ExampleObject(name = "No voucher", value = GetMoreApiExamples.NO_GETMORE_VOUCHER),
                    @ExampleObject(name = "No such code", value = GetMoreApiExamples.NO_GETMORE_VOUCHER_FOR_CODE)})),
            @ApiResponse(responseCode = "409", description = "A LIVE GetMore voucher (NOT_A_SIMULATED_VOUCHER), or"
                    + " closed already",
                    content = @Content(examples = {
                            @ExampleObject(name = "Not simulated", value = GetMoreApiExamples.NOT_A_SIMULATED_VOUCHER),
                            @ExampleObject(name = "Closed", value = GetMoreApiExamples.ALREADY_EXPIRED)}))
    })
    @Parameter(in = ParameterIn.HEADER, name = API_KEY_HEADER, required = true, description = KEY_DESCRIPTION)
    @PostMapping("/coupon-expiries")
    public ApiResult<SimActionResponse> expire(
            HttpServletRequest http,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(GetMoreApiExamples.EXPIRY_REQUEST)))
            @Valid @RequestBody SimCodeRequest request) {
        SimAction<SimActionResponse> done = actions.expire(request, caller(http));
        return ApiResult.ok(done.message(), done.data());
    }

    @Operation(summary = "Staging only (GETMORE_MODE=SIMULATED): the store's own view of a coupon",
            description = "The coupon (absent once withdrawn), its rule, its orders and credit memos, in Magento's"
                    + " shapes with codes masked (discount fields left out while unset, as Magento does), and the"
                    + " simulator's allowance and balance. Name it by loanReference or code, in the body.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.LOOKUP))),
            @ApiResponse(responseCode = "401", description = "Wrong or missing api key",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIMULATOR_KEY_REFUSED))),
            @ApiResponse(responseCode = "404", description = "No GetMore voucher for the loan (none, or a voucher of"
                    + " loans' own), or no coupon has the code", content = @Content(examples = {
                    @ExampleObject(name = "No voucher", value = GetMoreApiExamples.NO_GETMORE_VOUCHER),
                    @ExampleObject(name = "Not valid", value = GetMoreApiExamples.SIM_COUPON_NOT_FOUND)}))
    })
    @Parameter(in = ParameterIn.HEADER, name = API_KEY_HEADER, required = true, description = KEY_DESCRIPTION)
    @PostMapping("/coupon-lookups")
    public ApiResult<SimLookupResponse> lookup(
            HttpServletRequest http,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(GetMoreApiExamples.LOOKUP_REQUEST)))
            @Valid @RequestBody SimCodeRequest request) {
        SimAction<SimLookupResponse> done = actions.lookup(request, caller(http));
        return ApiResult.ok(done.message(), done.data());
    }

    @Operation(summary = "Staging only (GETMORE_MODE=SIMULATED): fail the store's next calls",
            description = "Queues a fault for the next 1 to 10 calls of one of the store's operations (GENERATE,"
                    + " SEARCH_COUPONS, GET_COUPON, UPDATE_COUPON, DELETE_COUPONS, SALES_RULE, ORDERS, CREDIT_MEMOS):"
                    + " UNAVAILABLE (not reached), REJECTED (a 400), or LOSE_RESPONSE (a write acts and its answer is"
                    + " lost; a read is unavailable). In memory, until used or cleared, or the pod restarts.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Queued",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.FAULT_QUEUED))),
            @ApiResponse(responseCode = "400", description = "A field missing or wrong",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "times": "times must be 1 to 10"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "Wrong or missing api key",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIMULATOR_KEY_REFUSED)))
    })
    @Parameter(in = ParameterIn.HEADER, name = API_KEY_HEADER, required = true, description = KEY_DESCRIPTION)
    @PostMapping("/faults")
    public ResponseEntity<ApiResult<SimFaultsResponse>> injectFault(
            HttpServletRequest http,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(GetMoreApiExamples.FAULT_REQUEST)))
            @Valid @RequestBody SimFaultRequest request) {
        SimAction<SimFaultsResponse> done = actions.injectFault(request, caller(http));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(done.message(), done.data()));
    }

    @Operation(summary = "Staging only (GETMORE_MODE=SIMULATED): clear every queued fault")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cleared",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.FAULTS_CLEARED))),
            @ApiResponse(responseCode = "401", description = "Wrong or missing api key",
                    content = @Content(examples = @ExampleObject(GetMoreApiExamples.SIMULATOR_KEY_REFUSED)))
    })
    @Parameter(in = ParameterIn.HEADER, name = API_KEY_HEADER, required = true, description = KEY_DESCRIPTION)
    @DeleteMapping("/faults")
    public ApiResult<SimFaultsResponse> clearFaults(HttpServletRequest http) {
        SimAction<SimFaultsResponse> done = actions.clearFaults(caller(http));
        return ApiResult.ok(done.message(), done.data());
    }

    /** 201 for what the action made, 200 for a repeat answered as before. */
    private static <T> ResponseEntity<ApiResult<T>> createdUnlessReplayed(SimAction<T> done) {
        return done.replayed() ? ResponseEntity.ok(ApiResult.ok(done.message(), done.data()))
                : ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.created(done.message(), done.data()));
    }

    /** Who called, for the audit: the address, X-Forwarded-For, and the key's fingerprint (never the key). */
    private static SimCaller caller(HttpServletRequest http) {
        return new SimCaller(http.getRemoteAddr(), http.getHeader("X-Forwarded-For"),
                SimulatorApiKey.fingerprint(http.getHeader(API_KEY_HEADER)));
    }
}
