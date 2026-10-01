package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.api.ForgotPasswordRequest;
import zw.co.innbucks.loans.core.api.LoginRequest;
import zw.co.innbucks.loans.core.api.LoginResponse;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.user.CreateUserService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

@Tag(name = "Authentication", description = "Sign-in and password recovery. No token needed.")
@RestController
@RequestMapping(ApiPaths.BASE + "/auth")
@RequiredArgsConstructor
@SecurityRequirements
@Slf4j
public class AuthController {

    static final String FORGOT_PASSWORD_MESSAGE =
            "If the account exists, a temporary password has been sent to its mobile number";

    private final AuthService authService;
    private final CreateUserService createUserService;

    @Operation(summary = "Sign in",
            description = "Returns a bearer token. When temporaryPassword is true (a new user, a super-admin"
                    + " password reset, the bootstrap admin) the token is good for one call only, PUT /me/password:"
                    + " every other call answers 403 PASSWORD_CHANGE_REQUIRED (\"Change your temporary password"
                    + " before continuing\") until the password is changed, and the token that change returns is"
                    + " unrestricted. Seven consecutive wrong passwords lock the account for 30 minutes; a"
                    + " super-admin password reset lifts the lock early.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in", content = @Content(examples = {
                    @ExampleObject(name = "Signed in", value = """
                            {
                              "code": "OK",
                              "message": "Success",
                              "data": {
                                "accessToken": "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiI3In0.c2lnbmF0dXJl",
                                "tokenType": "Bearer",
                                "expiresIn": 86400,
                                "temporaryPassword": false,
                                "groups": ["AGENTS"],
                                "merchantCode": "harare-motors",
                                "merchantName": "Harare Motor Spares"
                              }
                            }"""),
                    @ExampleObject(name = "Temporary password: change it first", value = """
                            {
                              "code": "OK",
                              "message": "Success",
                              "data": {
                                "accessToken": "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiI3In0.dGVtcG9yYXJ5",
                                "tokenType": "Bearer",
                                "expiresIn": 86400,
                                "temporaryPassword": true,
                                "groups": ["AGENTS"],
                                "merchantCode": "harare-motors",
                                "merchantName": "Harare Motor Spares"
                              }
                            }""")})),
            @ApiResponse(responseCode = "400", description = "Username or password missing",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "password": "Password is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "Wrong username or password",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "UNAUTHORIZED",
                              "message": "Invalid username or password"
                            }"""))),
            @ApiResponse(responseCode = "423", description = "Account locked; Retry-After says for how many seconds",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "ACCOUNT_LOCKED",
                              "message": "Account temporarily locked due to too many failed sign-in attempts",
                              "data": {
                                "lockedUntil": "2026-09-29T11:02:00+02:00",
                                "retryAfterSeconds": 1740
                              }
                            }"""))),
            @ApiResponse(responseCode = "500", description = "Server fault",
                    content = @Content(examples = @ExampleObject(ApiExamples.INTERNAL_ERROR)))
    })
    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Sign-in for user {}", request.getUsername());
        return ApiResult.ok(authService.login(request));
    }

    @Operation(summary = "Forgot password",
            description = "Sends a temporary password to the account's registered mobile number, by WhatsApp (SMS"
                    + " when WhatsApp fails). The answer is the same whether or not the username exists, so it cannot"
                    + " be used to find accounts; the message is sent in the background, so a delivery failure is not"
                    + " reported here either.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accepted", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "If the account exists, a temporary password has been sent to its mobile number"
                    }"""))),
            @ApiResponse(responseCode = "400", description = "Username missing",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "username": "Username is required"
                              }
                            }""")))
    })
    @PostMapping("/forgot-password")
    public ApiResult<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        log.info("Forgot-password request for user {}", request.getUsername());
        createUserService.resetPassword(request);
        return ApiResult.ok(FORGOT_PASSWORD_MESSAGE, null);
    }
}
