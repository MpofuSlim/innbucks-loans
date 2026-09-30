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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.authority.CreateCreditAuthorityLevelRequest;
import zw.co.innbucks.loans.core.authority.CreditAuthorityLevelResponse;
import zw.co.innbucks.loans.core.authority.CreditAuthorityService;
import zw.co.innbucks.loans.core.authority.UpdateCreditAuthorityLevelRequest;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Credit approval limits", description = "Credit authority levels (FR-PBL-028): each is the largest"
        + " principal someone at that level may approve, or any amount. A credit officer is given a level with PUT"
        + " /users/{userId}/credit-authority-level. While no level exists, nothing is limited. Once one does, an officer"
        + " approves only loans their level covers, an officer with no level approves nothing, and a loan above an"
        + " officer's level is referred to a higher one with POST /loans/{loanId}/credit-referral. SUPER_ADMIN may"
        + " approve any amount. Only approval is limited: rejecting or returning a loan pays nothing.")
@RestController
@RequestMapping(ApiPaths.BASE)
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class CreditAuthorityController {

    private final CreditAuthorityService creditAuthorityService;

    @Operation(summary = "List the credit authority levels",
            description = "Lowest limit first, the level with no limit (maximumPrincipal absent) last, each with the"
                    + " usernames given it. Empty while no limits are set up.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success",
                    content = @Content(examples = @ExampleObject(ApiExamples.CREDIT_AUTHORITY_LEVELS))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not CREDIT_MANAGER, FINANCE or SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN)))
    })
    @GetMapping("/credit-authority-levels")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','FINANCE','SUPER_ADMIN')")
    public ApiResult<List<CreditAuthorityLevelResponse>> levels() {
        return ApiResult.ok(creditAuthorityService.levels());
    }

    @Operation(summary = "Add a credit authority level",
            description = "SUPER_ADMIN only. Levels rank by their limit, so no two may share one and only one may"
                    + " have none (omit maximumPrincipal for any amount). The first level added puts limits on every"
                    + " approval from then on, so give credit officers their levels at the same time: an officer with"
                    + " no level approves nothing while limits apply. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(examples = @ExampleObject(ApiExamples.CREDIT_AUTHORITY_LEVEL_CREATED))),
            @ApiResponse(responseCode = "400", description = "A missing or bad field",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "code": "Code must be 3 to 40 capital letters, digits or underscores, starting with a letter",
                                "maximumPrincipal": "Maximum principal must be more than zero"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "The code is taken, or another level has the same limit",
                    content = @Content(examples = {
                            @ExampleObject(name = "Code taken", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Credit authority level SENIOR_CREDIT_OFFICER already exists"
                                    }"""),
                            @ExampleObject(name = "Same limit", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Credit officer already has a limit of 1000.00; each level needs its own"
                                    }"""),
                            @ExampleObject(name = "A second level with no limit", value = """
                                    {
                                      "code": "CONFLICT",
                                      "message": "Head of Credit already approves any amount; only one level can have no limit"
                                    }""")}))
    })
    @PostMapping("/credit-authority-levels")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<CreditAuthorityLevelResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.CREDIT_AUTHORITY_LEVEL_REQUEST)))
            @Valid @RequestBody CreateCreditAuthorityLevelRequest request) {
        return new ApiResult<>("CREATED", "Credit authority level created; it limits approvals by whoever is given it",
                creditAuthorityService.create(request));
    }

    @Operation(summary = "Change a credit authority level",
            description = "SUPER_ADMIN only. Replaces the level's name and limit (omit maximumPrincipal for any"
                    + " amount); it applies to the next approval by anyone who holds it. The code stays as created."
                    + " Audited with what it was.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Changed",
                    content = @Content(examples = @ExampleObject(ApiExamples.CREDIT_AUTHORITY_LEVEL_UPDATED))),
            @ApiResponse(responseCode = "400", description = "A missing or bad field",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "VALIDATION_ERROR",
                              "message": "Request validation failed",
                              "data": {
                                "name": "Name is required"
                              }
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such level",
                    content = @Content(examples = @ExampleObject(ApiExamples.CREDIT_AUTHORITY_LEVEL_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Another level has the same limit",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "Credit officer already has a limit of 1000.00; each level needs its own"
                            }""")))
    })
    @PutMapping("/credit-authority-levels/{code}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<CreditAuthorityLevelResponse> update(
            @PathVariable String code,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    examples = @ExampleObject(ApiExamples.CREDIT_AUTHORITY_LEVEL_UPDATE_REQUEST)))
            @Valid @RequestBody UpdateCreditAuthorityLevelRequest request) {
        return ApiResult.ok("Credit authority level updated; it applies to the next approval",
                creditAuthorityService.update(code, request));
    }

    @Operation(summary = "Remove a credit authority level",
            description = "SUPER_ADMIN only, and only for a level nobody holds. Removing the last level lifts every"
                    + " limit. Audited.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Removed",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "OK",
                              "message": "Credit authority level removed",
                              "data": null
                            }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "404", description = "No such level",
                    content = @Content(examples = @ExampleObject(ApiExamples.CREDIT_AUTHORITY_LEVEL_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Someone holds it",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "Senior credit officer is held by rnyathi; give them another level first"
                            }""")))
    })
    @DeleteMapping("/credit-authority-levels/{code}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<Void> delete(@PathVariable String code) {
        creditAuthorityService.delete(code);
        return ApiResult.ok("Credit authority level removed", null);
    }
}
