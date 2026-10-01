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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.borrower.BorrowerExchangeRequest;
import zw.co.innbucks.loans.core.borrower.BorrowerSession;
import zw.co.innbucks.loans.core.borrower.BorrowerSessionService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;
import zw.co.innbucks.loans.web.BorrowerApiExamples;

@Tag(name = "Borrower sign-in (SuperApp)", description = "How a Staff Grocery Loan borrower signs in from the SuperApp"
        + " (FR-SGL-025). The SuperApp user has already signed in at the InnBucks middleware with their PIN or"
        + " biometrics; the middleware signs a short-lived assertion that they did, and the app trades it here for a"
        + " borrower session. The session reaches the /borrower endpoints and nothing else, and lasts 15 minutes: the"
        + " app signs in again with a fresh assertion. No token needed.")
@RestController
@RequestMapping(ApiPaths.BASE + "/auth")
@RequiredArgsConstructor
@SecurityRequirements
public class BorrowerAuthController {

    private final BorrowerSessionService sessionService;

    @Operation(summary = "Sign a borrower in with the middleware's assertion",
            description = "Send the assertion the middleware returned when the SuperApp user signed in. Who the"
                    + " borrower is comes from the staff register, by the assertion's phone: a staff member who has"
                    + " left, or a phone not on the register, is refused NOT_ON_STAFF_REGISTER (show the message as it"
                    + " is). An assertion is good for one sign-in, and for at most five minutes after the middleware"
                    + " signed it: a second use, an expired one, or one signed for anything but the lending service is"
                    + " ASSERTION_REJECTED, always with the same message - get a fresh one and try again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.SESSION))),
            @ApiResponse(responseCode = "400", description = "No assertion sent",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.ASSERTION_MISSING))),
            @ApiResponse(responseCode = "401", description = "Forged, expired, already used or not for lending",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.ASSERTION_REJECTED))),
            @ApiResponse(responseCode = "403", description = "Genuine, but not a current staff member's phone",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.NOT_ON_STAFF_REGISTER))),
            @ApiResponse(responseCode = "503", description = "This server trusts no middleware key",
                    content = @Content(examples = @ExampleObject(BorrowerApiExamples.SIGN_IN_UNAVAILABLE))),
            @ApiResponse(responseCode = "500", description = "Server fault",
                    content = @Content(examples = @ExampleObject(ApiExamples.INTERNAL_ERROR)))
    })
    @PostMapping("/exchange")
    public ApiResult<BorrowerSession> exchange(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content =
            @Content(examples = @ExampleObject(BorrowerApiExamples.EXCHANGE_REQUEST)))
            @Valid @RequestBody BorrowerExchangeRequest request) {
        return ApiResult.ok(sessionService.signIn(request.getAssertion()));
    }
}
