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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.api.CreateMerchantRequest;
import zw.co.innbucks.loans.core.api.CreateUserRequest;
import zw.co.innbucks.loans.core.api.MerchantResponse;
import zw.co.innbucks.loans.core.api.UpdateMerchantRequest;
import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.merchant.MerchantService;
import zw.co.innbucks.loans.core.user.CreateUserService;
import zw.co.innbucks.loans.core.user.FindUserService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGrantPolicy;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;
import java.util.Set;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Merchants", description = "The businesses loans are originated for, where their loans are paid,"
        + " and their users.")
@RestController
@RequestMapping(ApiPaths.BASE + "/merchants")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class MerchantController {

    private static final String MERCHANT_OK = """
            {
              "code": "OK",
              "message": "Success",
              "data": """ + ApiExamples.MERCHANT + """

            }""";

    private final MerchantService merchantService;
    private final AuthService authService;
    private final CreateUserService createUserService;
    private final FindUserService findUserService;

    @Operation(summary = "List merchants",
            description = "Every merchant, by name. Account numbers are masked to their last four characters for"
                    + " everyone but SUPER_ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": [
                        """ + ApiExamples.MERCHANT + """

                      ]
                    }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @GetMapping
    public ApiResult<List<MerchantResponse>> listMerchants(JwtAuthenticationToken authentication) {
        boolean superAdmin = callerGroups(authentication).contains(UserGroup.SUPER_ADMIN);
        return ApiResult.ok(merchantService.findMerchants(!superAdmin));
    }

    @Operation(summary = "Create a merchant",
            description = "SUPER_ADMIN only. The merchant code, commission structure and commission group are fixed"
                    + " once created. The payout destination (disbursement type and account) is audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "CREATED",
                      "message": "Created",
                      "data": """ + ApiExamples.MERCHANT + """

                    }"""))),
            @ApiResponse(responseCode = "400", description = "A missing or invalid field",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "companyName": "Company name is required",
                                        "merchantCode": "Merchant code is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Wallet with no account", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Account number is required"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "The merchant code is taken",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "Merchant harare-motors already exists"
                            }""")))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<MerchantResponse> createMerchant(JwtAuthenticationToken authentication,
                                                      @Valid @RequestBody CreateMerchantRequest request) {
        return ApiResult.created(merchantService.createMerchant(request, authentication.getName()));
    }

    @Operation(summary = "Update a merchant",
            description = "SUPER_ADMIN only. Replaces the editable details; the code, commission structure and"
                    + " commission group cannot change. A change to the payout destination is audited, and applies"
                    + " to loans credit-approved from then on (an approval freezes where its loan is paid).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated", content = @Content(examples = @ExampleObject(MERCHANT_OK))),
            @ApiResponse(responseCode = "400", description = "A missing or invalid field",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_REQUEST",
                              "message": "Disbursement type is required"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such merchant",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "Merchant harare-motors not found"
                            }""")))
    })
    @PutMapping("/{merchantCode}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<MerchantResponse> updateMerchant(JwtAuthenticationToken authentication,
                                                      @PathVariable String merchantCode,
                                                      @RequestBody UpdateMerchantRequest request) {
        return ApiResult.ok(merchantService.updateMerchant(merchantCode, request, authentication.getName()));
    }

    @Operation(summary = "List a merchant's users", description = "SUPER_ADMIN and CREDIT_MANAGER only.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": [
                        """ + ApiExamples.AGENT_USER + """

                      ]
                    }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN or CREDIT_MANAGER",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/{merchantCode}/users")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CREDIT_MANAGER')")
    public ApiResult<List<UserResponse>> listUsers(@PathVariable String merchantCode) {
        return ApiResult.ok(authService.findUsersByMerchantCode(merchantCode));
    }

    @Operation(summary = "Create a user in a merchant",
            description = "SUPER_ADMIN only: an agent, a credit manager or finance. The user receives a temporary"
                    + " password by SMS and must change it at first sign-in. commissionGroupId is required unless the"
                    + " merchant's commission structure is MERCHANT_DEFINED; 0 means the default group.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "CREATED",
                      "message": "Created",
                      "data": """ + ApiExamples.AGENT_USER + """

                    }"""))),
            @ApiResponse(responseCode = "400", description = "A missing or invalid field",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "group": "User group is required",
                                "mobileNumber": "Mobile number is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such merchant",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "Merchant harare-motors not found"
                            }"""))),
            @ApiResponse(responseCode = "409", description = "The username is taken",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "USERNAME_TAKEN",
                              "message": "User tmoyo already exists"
                            }""")))
    })
    @PostMapping("/{merchantCode}/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<UserResponse> createUser(JwtAuthenticationToken authentication,
                                              @PathVariable String merchantCode,
                                              @Valid @RequestBody CreateUserRequest request) {
        User caller = findUserService.resolveUserFromAccessToken(authentication.getToken())
                .orElseThrow(() -> new AccessDeniedException("Unable to resolve user from token"));
        // Group (body) and merchant (path) are both caller-chosen, so the grant policy is checked as well as
        // the role gate: widening the gate alone must never let a caller hand out a group, or reach a
        // merchant, that it may not.
        UserGrantPolicy.checkMayCreate(callerGroups(authentication), caller.getMerchant().getMerchantCode(),
                request.getGroup(), merchantCode);
        return ApiResult.created(createUserService.create(request, merchantCode));
    }

    private static Set<UserGroup> callerGroups(JwtAuthenticationToken authentication) {
        return UserGrantPolicy.callerGroups(authentication.getAuthorities());
    }
}
