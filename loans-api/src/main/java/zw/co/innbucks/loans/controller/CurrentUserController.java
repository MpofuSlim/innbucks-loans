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
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.api.LoginRequest;
import zw.co.innbucks.loans.core.api.LoginResponse;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.loan.LoanService;
import zw.co.innbucks.loans.core.loan.SalesSummaryResponse;
import zw.co.innbucks.loans.core.user.FindUserService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.ChangePasswordRequest;

import java.time.LocalDate;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Current user", description = "The signed-in user's own account and figures.")
@RestController
@RequestMapping(ApiPaths.BASE + "/me")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
@Slf4j
public class CurrentUserController {

    private final AuthService authService;
    private final FindUserService findUserService;
    private final LoanService loanService;
    private final MarketTimeZone marketTimeZone;

    @Operation(summary = "Change my password",
            description = "The new password must be 8 to 72 characters, must not begin or end with a space, and"
                    + " must differ from the current one. Every token issued before the change stops working; the"
                    + " response carries a fresh one, which the caller must use from here on. A wrong current"
                    + " password counts towards the sign-in lockout.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed; a new token",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "OK",
                              "message": "Password changed",
                              "data": {
                                "accessToken": "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiI3In0.bmV3c2lnbmF0dXJl",
                                "tokenType": "Bearer",
                                "expiresIn": 86400,
                                "temporaryPassword": false,
                                "groups": ["AGENTS"],
                                "merchantCode": "harare-motors",
                                "merchantName": "Harare Motor Spares"
                              }
                            }"""))),
            @ApiResponse(responseCode = "400", description = "Wrong current password, or a new one the rules refuse",
                    content = @Content(examples = {
                            @ExampleObject(name = "Wrong current password", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Current password is incorrect"
                                    }"""),
                            @ExampleObject(name = "Too short", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "The password must be at least 8 characters"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "423", description = "Locked by too many wrong passwords",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "ACCOUNT_LOCKED",
                              "message": "Account temporarily locked due to too many failed sign-in attempts",
                              "data": {
                                "lockedUntil": "2026-09-29T11:02:00+02:00",
                                "retryAfterSeconds": 1740
                              }
                            }""")))
    })
    @PutMapping("/password")
    public ApiResult<LoginResponse> changePassword(@AuthenticationPrincipal Jwt token,
                                                   @Valid @RequestBody ChangePasswordRequest request) {
        String username = token.getClaimAsString("preferred_username");
        try {
            authService.login(LoginRequest.builder().username(username).password(request.getCurrentPassword()).build());
        } catch (BadCredentialsException ex) {
            // A 401 here would read to the client as an expired session and sign the user out.
            throw new ValidationException("Current password is incorrect");
        }
        authService.resetPassword(request.getNewPassword(), token.getSubject(), username);
        LoginResponse session = authService.login(
                LoginRequest.builder().username(username).password(request.getNewPassword()).build());
        return ApiResult.ok("Password changed", session);
    }

    @Operation(summary = "My sales",
            description = "Loans the signed-in user originated that were disbursed in the period: how many, their"
                    + " principal and the agent commission earned. Dates are the market's calendar days, both"
                    + " inclusive; the period defaults to month to date.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": {
                        "loanCount": 1,
                        "totalPrincipal": 531.91,
                        "totalAgentCommission": 3.19
                      }
                    }"""))),
            @ApiResponse(responseCode = "400", description = "A date not in yyyy-MM-dd",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "INVALID_PARAMETER",
                              "message": "Invalid value for 'fromDate'"
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @GetMapping("/sales")
    public ApiResult<SalesSummaryResponse> mySales(
            @AuthenticationPrincipal Jwt token,
            @Parameter(description = "yyyy-MM-dd; the first of this month when omitted", example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @Parameter(description = "yyyy-MM-dd; today when omitted", example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        LocalDate today = marketTimeZone.today();
        LocalDate from = fromDate == null ? today.withDayOfMonth(1) : fromDate;
        LocalDate to = toDate == null ? today : toDate;
        User user = findUserService.resolveUserFromAccessToken(token)
                .orElseThrow(() -> new AccessDeniedException("Unable to resolve user from token"));
        return ApiResult.ok(loanService.getSalesSummary(user.getId(), from, to));
    }
}
