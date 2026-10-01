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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.api.PasswordResetRequest;
import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.authority.CreditAuthorityService;
import zw.co.innbucks.loans.core.authority.UserCreditAuthorityRequest;
import zw.co.innbucks.loans.core.user.AdminPasswordResetService;
import zw.co.innbucks.loans.core.user.PasswordResetOutcome;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Users", description = "Administration of other users' accounts. Users are created under their"
        + " merchant: POST /merchants/{merchantCode}/users.")
@RestController
@RequestMapping(ApiPaths.BASE + "/users")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class UserController {

    private final AdminPasswordResetService adminPasswordResetService;
    private final CreditAuthorityService creditAuthorityService;

    @Operation(summary = "Reset a user's password",
            description = "SUPER_ADMIN only. Sets a fresh temporary password and delivers it: by WhatsApp unless a"
                    + " channel is named, falling back to SMS when WhatsApp fails; SMS or EMAIL when named. The"
                    + " message names InnBucks Lending, the username and the sign-in address. Delivery comes"
                    + " first: if it fails nothing changes and the old password still works. The user's existing"
                    + " sessions end and any sign-in lock is lifted. The password is never returned; the message says"
                    + " which channel delivered it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reset and delivered",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "OK",
                              "message": "Temporary password sent by WHATSAPP",
                              "data": """ + ApiExamples.AGENT_USER + """

                            }"""))),
            @ApiResponse(responseCode = "400", description = "Nowhere on file to send it",
                    content = @Content(examples = {
                            @ExampleObject(name = "No mobile number on file", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "User tmoyo has no mobile number on file"
                                    }"""),
                            @ExampleObject(name = "No email on file", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "User tmoyo has no email address on file"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such user",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "User 7 not found"
                            }"""))),
            @ApiResponse(responseCode = "502", description = "The channel refused or could not be reached (for WhatsApp,"
                    + " then SMS too); nothing changed",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOTIFICATION_FAILED",
                              "message": "WhatsApp gateway rejected the message: HTTP 400"
                            }""")))
    })
    @PostMapping("/{userId}/password-reset")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<UserResponse> resetPassword(@PathVariable Long userId,
                                                 @Valid @RequestBody(required = false) PasswordResetRequest request) {
        PasswordResetOutcome reset = adminPasswordResetService.resetPassword(userId,
                request == null ? null : request.getChannel());
        return ApiResult.ok("Temporary password sent by " + reset.sentBy(), reset.user());
    }

    @Operation(summary = "Set a user's credit approval limit",
            description = "SUPER_ADMIN only. Gives the user a credit authority level (GET /credit-authority-levels),"
                    + " which caps the principal they may approve once any level is set up (FR-PBL-028); a null or"
                    + " omitted level takes theirs away. An agent cannot be given one, and SUPER_ADMIN needs none: they"
                    + " may approve any amount. Applies to the user's next approval. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Set; the user as they now stand",
                    content = @Content(examples = @ExampleObject(ApiExamples.USER_CREDIT_AUTHORITY_CHANGED))),
            @ApiResponse(responseCode = "400", description = "No such level, or the user is an agent or SUPER_ADMIN",
                    content = @Content(examples = {
                            @ExampleObject(name = "Unknown level", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Unknown credit authority level JUNIOR_OFFICER"
                                    }"""),
                            @ExampleObject(name = "Agent", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "tmoyo is an agent; agents originate applications and never approve them"
                                    }"""),
                            @ExampleObject(name = "SUPER_ADMIN", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "admin is SUPER_ADMIN, who may approve any amount; a credit authority level would not limit them"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such user",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "NOT_FOUND",
                              "message": "User 7 not found"
                            }""")))
    })
    @PutMapping("/{userId}/credit-authority-level")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<UserResponse> setCreditAuthorityLevel(
            @PathVariable Long userId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.USER_CREDIT_AUTHORITY_REQUEST)))
            @Valid @RequestBody UserCreditAuthorityRequest request) {
        UserResponse user = creditAuthorityService.assign(userId, request.getLevel());
        return ApiResult.ok(user.creditAuthorityLevel() == null ? "Credit authority level removed"
                : "Credit authority level set to " + user.creditAuthorityLevel(), user);
    }
}
