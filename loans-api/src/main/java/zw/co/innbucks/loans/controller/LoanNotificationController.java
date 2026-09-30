package zw.co.innbucks.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.loan.LoanReadScopeResolver;
import zw.co.innbucks.loans.core.notice.LoanNotificationResponse;
import zw.co.innbucks.loans.core.notice.LoanNotificationService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Loan notifications", description = "What the applicant was told about their application, and when"
        + " (FR-SSB-016). The applicant is sent an SMS at every stage: received, sent to SSB, SSB's answer, Credit's"
        + " decision or request for more information, the answer to it, the payout or a delay in it, and an"
        + " application that could not be sent to SSB. Each is sent once the stage is saved, never for one that was"
        + " rolled back, and every attempt is kept, sent or not.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class LoanNotificationController {

    private final LoanNotificationService loanNotificationService;
    private final LoanReadScopeResolver loanReadScopeResolver;

    @Operation(summary = "What the applicant was told",
            description = "Every message sent to the applicant about the loan, oldest first: the notice, the stage it"
                    + " told them the application had reached, the text as sent, and whether the SMS gateway accepted"
                    + " it (sent), or why not (failureReason). gatewayReference traces a message at the gateway."
                    + " A loan outside the caller's scope is answered exactly like one that does not exist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success; an empty list for a loan nothing was sent about",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_43_NOTIFICATIONS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "404", description = "No such loan, or not one the caller may read",
                    content = @Content(examples = @ExampleObject(ApiExamples.LOAN_NOT_FOUND)))
    })
    @GetMapping("/loans/{loanId}/notifications")
    public ApiResult<List<LoanNotificationResponse>> notifications(JwtAuthenticationToken authentication,
                                                                   @PathVariable Long loanId) {
        return ApiResult.ok(loanNotificationService.history(loanId,
                loanReadScopeResolver.resolve(authentication.getToken())));
    }
}
