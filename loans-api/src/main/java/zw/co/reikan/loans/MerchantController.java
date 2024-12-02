package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import zw.co.reikan.loans.core.api.*;
import zw.co.reikan.loans.core.keycloak.KeycloakService;
import zw.co.reikan.loans.core.loan.LoanService;
import zw.co.reikan.loans.core.merchant.FindMerchantsResponse;
import zw.co.reikan.loans.core.merchant.MerchantService;
import zw.co.reikan.loans.core.user.*;

import java.security.Principal;
import java.util.List;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;


@Tag(name = "MERCHANTS",
        description = "### Please Note:\n" +
                "1. Auth credentials and endpoint will be provided\n" +
                "2. Please contact  _support@bulkit.co.zw_ for support.\n")
@RestController
@RequestMapping(value = "/api/merchants")
@RequiredArgsConstructor
@Slf4j
public class MerchantController {

    private final MerchantService merchantService;
    private final KeycloakService keycloakService;
    private final CreateUserService createUserService;
    private final LoanService loanService;
    private final FindUserService findUserService;


    @Operation(summary = "FIND MERCHANTS",
            description = "List all merchants",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @GetMapping
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<FindMerchantsResponse> findAllMerchants() {
        FindMerchantsResponse merchants = merchantService.findMerchants();
        return ResponseEntity.ok(merchants);
    }


    @PostMapping
    @Operation(summary = "CREATE MERCHANT",
            description = "Create new merchant",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<MerchantDto> createMerchant(@RequestBody CreateMerchantRequest createMerchantRequest) {
        MerchantDto merchant = merchantService.createMerchant(createMerchantRequest);
        return ResponseEntity.ok(merchant);
    }

    @Operation(summary = "CREATE AGENT",
            description = "Create merchant Agent or User",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @PostMapping({"/{merchantCode}/agents"})
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<SaveUserResponse> createAgent(Principal principal,
                                                        @RequestBody CreateAgentRequest createUserRequest,
                                                        @PathVariable String merchantCode) {
        Jwt token = ((JwtAuthenticationToken) principal).getToken();
        User loggedInUser = findUserService.resolveUserFromAccessToken(token)
                .orElseThrow(() -> new RuntimeException("Unable to resolve user from token"));
        User agent = UserGroup.SUB_AGENTS == createUserRequest.getGroup() ? loggedInUser : null;
        CreateUserResponse createUserResponse = createUserService.create(createUserRequest, agent, merchantCode);
        SaveUserResponse saveUserResponse = new SaveUserResponse();
        saveUserResponse.setUser(createUserResponse.user());
        return ResponseEntity.ok(saveUserResponse);
    }

    @Operation(summary = "CREATE SALES CONSULTANT",
            description = "Add a sales consultant.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @PostMapping({"/agents/{externalSystemId}/sales-consultant"})
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<SaveUserResponse> createSalesConsultant(Principal principal,
                                                                  @RequestBody CreateAgentRequest createUserRequest,
                                                                  @PathVariable String externalSystemId) {

        log.info("Creating sales consultant for user {}", externalSystemId);

        Jwt token = ((JwtAuthenticationToken) principal).getToken();
        boolean canAddSubAgent = findUserService.hasAnyRole(token, List.of(UserGroup.AGENTS.name(),
                UserGroup.ORGANISATION_SUPER_USER.name(), UserGroup.RETAIL_SALES.name()));
        if (!canAddSubAgent) {
            throw new RuntimeException("Can't add sub agent");
        }
        User agent = findUserService.findUserExternalSystemId(externalSystemId)
                .orElseThrow(() -> new RuntimeException(String.format("User not found for %s - ", externalSystemId)));
        createUserRequest.setGroup(UserGroup.SUB_AGENTS);
        CreateUserResponse createUserResponse = createUserService.create(createUserRequest, agent,
                agent.getMerchant().getMerchantCode());
        SaveUserResponse saveUserResponse = new SaveUserResponse();
        saveUserResponse.setUser(createUserResponse.user());
        return ResponseEntity.ok(saveUserResponse);
    }

    @Operation(summary = "CREATE USER",
            description = "Create merchant User",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @PostMapping({"/{merchantCode}/users"})
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<SaveUserResponse> createUser(Principal principal,
                                                       @RequestBody CreateAgentRequest createUserRequest,
                                                       @PathVariable String merchantCode) {
        return createAgent(principal, createUserRequest, merchantCode);
    }

    @Operation(summary = "FIND AGENTS",
            description = "List all agents for the given merchant",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @GetMapping("/{code}/agents")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<UserListingResponse> findAgentsForMerchant(@PathVariable String code) {
        List<UserDTO> keycloakUsers = keycloakService.findUsersByMerchantCode(code);
        return ResponseEntity.ok(UserListingResponse.builder().users(keycloakUsers).build());
    }

//    @Operation(summary = "FIND LOANS",
//            description = "List all loans for the given merchant",
//            security = {@SecurityRequirement(name = BEARER_TOKEN)}
//    )
//    @GetMapping("/{code}/loans")
//    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
//            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
//            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
//            @ApiResponse(responseCode = "500", description = "Processing error")})
//    public ResponseEntity<UserListingResponse> findAgentsForMerchant(@PathVariable String code) {
//        List<UserDTO> keycloakUsers = keycloakService.findUsersByMerchantCode(code);
//        return ResponseEntity.ok(UserListingResponse.builder().users(keycloakUsers).build());
//    }


    @Operation(summary = "FIND LOANS",
            description = "List all loans for the given merchant",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200",
                    description = "Request received for processing",
                    content = {@Content(mediaType = "application/json",
                            schema = @Schema(implementation = LoansWrapper.class))}),
            @ApiResponse(responseCode = "400",
                    description = "Represents an Error Caused by the Violation of a Business Rule"),

            @ApiResponse(responseCode = "500",
                    description = "Represents an Error Caused by a System Malfunction")
    })
    @PostMapping("/{code}/loans")
    public LoansWrapper findLoans(Principal principal,
                                  @RequestBody FindLoansRequest request,
                                  @PathVariable String code) {
        log.info("Find loan request: {}", request);
        Long userId = resolveUserId(principal, code);
        FindLoansInternalRequest internalRequest = FindLoansInternalRequest
                .builder()
                .userId(userId)
                .merchantCode(code)
                .approvalStatus(request.getApprovalStatus())
                .internalApprovalStatus(request.getInternalApprovalStatus())
                .disbursementStatus(request.getDisbursementStatus())
                .fromDate(request.getFromDate())
                .toDate(request.getToDate())
                .build();
        return new LoansWrapper(loanService.findLoansForMerchant(internalRequest));
    }

    public Long resolveUserId(Principal principal, String merchantCode) {
        Jwt token = ((JwtAuthenticationToken) principal).getToken();
        User user = findUserService.resolveUserFromAccessToken(token)
                .orElseThrow(() -> new RuntimeException("Unable to resolve user from token"));
        boolean isSuperAdmin = findUserService.hasAnyRole(token, List.of(UserGroup.BULKIT_ADMIN.name(),
                UserGroup.RETAIL_SALES.name()));
        if (isSuperAdmin) {
            return null;
        }
        boolean isOrgSuperUser = findUserService.hasRole(token, UserGroup.ORGANISATION_SUPER_USER.name());
        if (isOrgSuperUser) {
            if (!user.getMerchant().getMerchantCode().equalsIgnoreCase(merchantCode)) {
                throw new AccessDeniedException("User not allowed to complete this operation");
            }
            return null;
        }
        if (!user.getMerchant().getMerchantCode().equalsIgnoreCase(merchantCode)) {
            throw new AccessDeniedException("User not allowed to complete this operation");
        }
        return user.getId();
    }
}
