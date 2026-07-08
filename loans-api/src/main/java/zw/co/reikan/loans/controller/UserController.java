package zw.co.reikan.loans.controller;
import zw.co.reikan.loans.dto.ChangePasswordRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.api.AdminResetPasswordRequest;
import zw.co.reikan.loans.core.api.AuthRequest;
import zw.co.reikan.loans.core.api.AuthResponse;
import zw.co.reikan.loans.core.api.UserDTO;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.user.AdminPasswordResetService;
import zw.co.reikan.loans.core.user.CreateUserService;

import java.security.Principal;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "USERS")
@RestController
@RequestMapping(value = "/api/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    private final CreateUserService createUserService;

    private final AdminPasswordResetService adminPasswordResetService;

    @Operation(summary = "RESET PASSWORD",
            description = "Reset user password",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @PostMapping(value = "/reset-password")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public AuthResponse updatePassword(Principal principal,
                                       @RequestBody ChangePasswordRequest changePasswordRequest) {

        Jwt token = ((JwtAuthenticationToken) principal).getToken();

        authService.login(AuthRequest.builder()
                .username(token.getClaimAsString("preferred_username"))
                .password(changePasswordRequest.getCurrentPassword()).build());

        authService.resetPassword(changePasswordRequest.getNewPassword(), token.getSubject(),
                token.getClaimAsString("preferred_username"));

        return authService.login(AuthRequest.builder()
                .username(token.getClaimAsString("preferred_username"))
                .password(changePasswordRequest.getNewPassword()).build());
    }

    @Operation(operationId = "adminResetPassword",
            summary = "ADMIN RESET PASSWORD",
            description = "Super-admin resets a user's password to a fresh temporary one and delivers it over the "
                    + "chosen channel (EMAIL or SMS). The password is delivered before it is applied — if delivery "
                    + "fails the reset is aborted and the old password stays valid. The temporary password is never "
                    + "returned in the response.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @PostMapping(value = "/admin-reset-password")
    @PreAuthorize("hasRole('BULKIT_ADMIN')")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Password reset and temporary password delivered"),
            @ApiResponse(responseCode = "400", description = "Unknown user, missing channel, or no email/mobile on file"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Forbidden. caller is not a super admin"),
            @ApiResponse(responseCode = "502", description = "Notification channel rejected or unreachable; password unchanged")})
    public UserDTO adminResetPassword(@RequestBody AdminResetPasswordRequest request) {
        return adminPasswordResetService.resetPassword(request);
    }
}
