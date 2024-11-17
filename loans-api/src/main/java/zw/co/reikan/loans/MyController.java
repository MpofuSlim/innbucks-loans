package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.api.FindLoansInternalRequest;
import zw.co.reikan.loans.core.api.LoanStatisticsResponse;
import zw.co.reikan.loans.core.api.UserDTO;
import zw.co.reikan.loans.core.loan.BaseEntity;
import zw.co.reikan.loans.core.loan.LoanService;
import zw.co.reikan.loans.core.user.FindUserService;

import java.security.Principal;
import java.time.LocalDate;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "MY")
@RestController
@RequestMapping(value = "/api/me")
@RequiredArgsConstructor
@Slf4j
public class MyController {

    private final FindUserService findUserService;
    private final LoanService loanService;

    @Operation(summary = "MY SALES CONSULTANTS",
            description = "Find sales consultants for the logged in user",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @GetMapping("/sales-consultants")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public Page<UserDTO> mySalesConsultants(Principal principal,
                                            @RequestParam(required = false, defaultValue = "0") int page,
                                            @RequestParam(required = false, defaultValue = "10") int size) {

        Jwt token = ((JwtAuthenticationToken) principal).getToken();

        return findUserService.resolveUserFromAccessToken(token)
                .map(BaseEntity::getId)
                .map(id -> findUserService.findSalesConsultants(id, PageRequest.of(page, size))
                        .map(UserDTO::fromUser))
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @Operation(summary = "MY SALES STATS",
            description = "Find sales stats for the logged in user",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @GetMapping("/sales-stats")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public LoanStatisticsResponse mySalesStats(Principal principal,
                                               @RequestParam(required = false) LocalDate fromDate,
                                               @RequestParam(required = false) LocalDate toDate) {
        Jwt token = ((JwtAuthenticationToken) principal).getToken();
        LocalDate fromDateToUse = fromDate == null ? LocalDate.now().withDayOfMonth(1) : fromDate;
        LocalDate toDateToUse = toDate == null ? LocalDate.now() : toDate;
        log.info("getting sales stats between {} and {}", fromDateToUse, toDateToUse);

        return findUserService.resolveUserFromAccessToken(token)
                .map(u -> loanService.getStatistics(FindLoansInternalRequest.builder()
                        .fromDate(fromDateToUse)
                        .toDate(toDateToUse)
                        .userId(u.getId())
                        .build()))
                .orElseThrow(() -> new RuntimeException("User not found"));
    }
}
