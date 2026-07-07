package zw.co.reikan.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.api.CommissionGroupDto;
import zw.co.reikan.loans.core.api.CommissionGroupResponse;
import zw.co.reikan.loans.core.api.CreateCommissionGroupRequest;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.commission.CommissionGroupService;

import java.util.List;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "SYSTEM PARAMETERS")
@RestController
@RequestMapping(value = "/api/parameters")
@RequiredArgsConstructor
public class SystemParametersController {

    private final CommissionGroupRepository commissionGroupRepository;
    private final CommissionGroupService commissionGroupService;

    @Operation(summary = "COMMISSION GROUPS",
            description = "Find commission groups",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @GetMapping(value = "/commission-groups")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public CommissionGroupResponse findCommissionGroups() {
        List<CommissionGroupDto> commissionGroups = commissionGroupRepository.findCommissionGroupByEnabled(true)
                .stream().map(CommissionGroupDto::fromCommissionGroup)
                .toList();
        return CommissionGroupResponse.builder().commissionGroups(commissionGroups).build();
    }

    @Operation(operationId = "createCommissionGroup",
            summary = "CREATE COMMISSION GROUP",
            description = "Create a new commission group. Percentage groups must have agent + provider commission summing to 100.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @PostMapping(value = "/commission-groups")
    @PreAuthorize("hasRole('BULKIT_ADMIN')")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing or invalid fields"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Forbidden. caller lacks the BULKIT_ADMIN role"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public CommissionGroupDto createCommissionGroup(@RequestBody CreateCommissionGroupRequest request) {
        return commissionGroupService.create(request);
    }
}
