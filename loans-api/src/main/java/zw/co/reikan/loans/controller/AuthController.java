package zw.co.reikan.loans.controller;

import jakarta.validation.Valid;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.api.AuthRequest;
import zw.co.reikan.loans.core.api.AuthResponse;
import zw.co.reikan.loans.core.api.ForgotPasswordRequest;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.user.CreateUserService;

@RestController
@Slf4j
@RequestMapping("/api")
@Tag(name = "AUTHENTICATION")
public class AuthController {

    @Autowired
    private AuthService authService;

    @Autowired
    private CreateUserService createUserService;


    @Operation(summary = "GET ACCESS TOKEN",
            description = "When provided with valid login credentials, returns an access token"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Authenticated"),
            @ApiResponse(responseCode = "401",
                    description = "Invalid credentials"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/auth/token")
    public AuthResponse authenticate(@Valid @RequestBody AuthRequest authRequest) {
        try {
            log.info("Authenticating user: {}", authRequest.getUsername());
            return authService.login(authRequest);
        } catch (Exception ex) {
            log.error("Error getting access token.", ex);
            throw new BadCredentialsException(ex.getMessage());
        }
    }

    @Operation(summary = "FORGOT PASSWORD",
            description = "Sends a temporary password to the registered mobile number"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Authenticated"),
            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/auth/forgot-password")
    public ResponseEntity forgotPassword(@Valid @RequestBody ForgotPasswordRequest authRequest) {
        try {
            log.info("Resetting password for user: {}", authRequest.getUsername());
            createUserService.resetPassword(authRequest);
            return ResponseEntity.ok().build();
        } catch (Exception ex) {
            log.error("Error getting access token.", ex);
            throw new BadCredentialsException(ex.getMessage());
        }
    }
}
