package zw.co.reikan.loans.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.api.DashboardStatsResponse;
import zw.co.reikan.loans.core.dashboard.DashboardService;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "DASHBOARD")
@RestController
@RequestMapping(value = "/api")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(operationId = "dashboardStats",
            summary = "DASHBOARD STATS",
            description = "Platform-wide admin dashboard snapshot: loan counts by status, pending approvals, "
                    + "disbursement/commission totals and entity counts, in a single call.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Forbidden. caller lacks an admin role"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    @GetMapping("/dashboard-stats")
    @PreAuthorize("hasAnyRole('BULKIT_ADMIN','ORGANISATION_SUPER_USER','RETAIL_SALES')")
    public DashboardStatsResponse dashboardStats() {
        return dashboardService.getDashboardStats();
    }
}
