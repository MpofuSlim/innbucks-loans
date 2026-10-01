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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.borrower.BorrowerProfile;
import zw.co.innbucks.loans.core.borrower.BorrowerSessionService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.BorrowerApiExamples;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Borrower (SuperApp)", description = "The Staff Grocery Loan, as the borrower sees it in the SuperApp."
        + " Signed in with a borrower session (POST /auth/exchange), which reaches these endpoints and nothing else;"
        + " every other session is refused here. Each call acts for the staff member the session names: there is no"
        + " path or parameter naming anyone else.")
@RestController
@RequestMapping(ApiPaths.BASE + "/borrower")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class BorrowerController {

    private final BorrowerSessionService sessionService;

    @Operation(summary = "Who is signed in",
            description = "BORROWER. The staff member the session is for, from the staff register, and how they"
                    + " authenticated at the middleware (signedInWith: pin, fpt for a fingerprint, face).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.PROFILE))),
            @ApiResponse(responseCode = "401", description = "No valid borrower session, or the staff member has left"
                    + " or changed number since signing in",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not a borrower session",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/me")
    @PreAuthorize("hasRole('BORROWER')")
    public ApiResult<BorrowerProfile> me(@Parameter(hidden = true) JwtAuthenticationToken authentication) {
        Jwt token = authentication.getToken();
        List<String> methods = token.getClaimAsStringList(JwtService.AUTHENTICATION_METHODS_CLAIM);
        return ApiResult.ok(sessionService.profile(JwtService.borrowerStaffMemberId(token),
                methods == null ? List.of() : methods));
    }
}
