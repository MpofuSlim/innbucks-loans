package zw.co.reikan.loans;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.api.AuthRequest;
import zw.co.reikan.loans.core.api.AuthResponse;
import zw.co.reikan.loans.core.keycloak.KeyCloakServiceImpl;

@RestController
@Slf4j
@RequestMapping
@Tag(name = "AUTHENTICATION")
public class AuthController {

    @Autowired
    private KeyCloakServiceImpl keyCloakService;


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
    public AuthResponse authenticate(@RequestBody AuthRequest authRequest) {
        try {
            log.info("Authenticating user: {}", authRequest.getUsername());
            return keyCloakService.login(authRequest);
        } catch (Exception ex) {
            log.error("Error getting access token.", ex);
            throw new BadCredentialsException(ex.getMessage());
        }
    }
}
