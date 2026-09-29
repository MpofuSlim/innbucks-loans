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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import zw.co.innbucks.loans.core.api.CommissionGroupResponse;
import zw.co.innbucks.loans.core.api.CreateCommissionGroupRequest;
import zw.co.innbucks.loans.core.commission.CommissionGroupService;
import zw.co.innbucks.loans.web.ApiExamples;
import zw.co.innbucks.loans.web.ApiPaths;
import zw.co.innbucks.loans.web.ApiResult;

import java.util.List;

import static zw.co.innbucks.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "Commission groups", description = "How a loan's commission is split between the agent and the provider.")
@RestController
@RequestMapping(ApiPaths.BASE + "/commission-groups")
@RequiredArgsConstructor
@SecurityRequirement(name = BEARER_TOKEN)
public class CommissionGroupController {

    private final CommissionGroupService commissionGroupService;

    @Operation(summary = "List commission groups", description = "The groups that can be assigned, by name.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Success", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "OK",
                      "message": "Success",
                      "data": [
                        {
                          "id": 2,
                          "name": "100-Favouring-InnBucks",
                          "agentCommission": 0.0,
                          "providerCommission": 100.0,
                          "percentage": true
                        },
                        """ + ApiExamples.COMMISSION_GROUP + """
            ,
                        {
                          "id": 3,
                          "name": "Default-InnBucks",
                          "agentCommission": 0.0,
                          "providerCommission": 100.0,
                          "percentage": true
                        }
                      ]
                    }"""))),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED)))
    })
    @GetMapping
    public ApiResult<List<CommissionGroupResponse>> listCommissionGroups() {
        return ApiResult.ok(commissionGroupService.findEnabled());
    }

    @Operation(summary = "Create a commission group",
            description = "SUPER_ADMIN only. With percentage true the two commissions are shares of the total and"
                    + " must add up to 100; otherwise they are amounts.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created", content = @Content(examples = @ExampleObject("""
                    {
                      "code": "CREATED",
                      "message": "Created",
                      "data": {
                        "id": 4,
                        "name": "70-30-Favouring-InnBucks",
                        "agentCommission": 30,
                        "providerCommission": 70,
                        "percentage": true
                      }
                    }"""))),
            @ApiResponse(responseCode = "400", description = "A missing or invalid field",
                    content = @Content(examples = {
                            @ExampleObject(name = "Missing fields", value = """
                                    {
                                      "code": "VALIDATION_ERROR",
                                      "message": "Request validation failed",
                                      "data": {
                                        "name": "Commission group name is required"
                                      }
                                    }"""),
                            @ExampleObject(name = "Percentages", value = """
                                    {
                                      "code": "INVALID_REQUEST",
                                      "message": "Agent and provider percentages must add up to 100"
                                    }""")})),
            @ApiResponse(responseCode = "401", description = "No valid token",
                    content = @Content(examples = @ExampleObject(ApiExamples.UNAUTHORIZED))),
            @ApiResponse(responseCode = "403", description = "Caller is not SUPER_ADMIN",
                    content = @Content(examples = @ExampleObject(ApiExamples.FORBIDDEN))),
            @ApiResponse(responseCode = "409", description = "The name is taken",
                    content = @Content(examples = @ExampleObject("""
                            {
                              "code": "CONFLICT",
                              "message": "Commission group 70-30-Favouring-InnBucks already exists"
                            }""")))
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResult<CommissionGroupResponse> createCommissionGroup(@Valid @RequestBody CreateCommissionGroupRequest request) {
        return ApiResult.created(commissionGroupService.create(request));
    }
}
