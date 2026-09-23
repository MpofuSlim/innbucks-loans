package zw.co.reikan.loans.controller;

import jakarta.validation.Valid;
import zw.co.reikan.loans.dto.LoansWrapper;

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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import zw.co.reikan.loans.core.api.*;
import zw.co.reikan.loans.core.auth.AuthService;
import zw.co.reikan.loans.core.loan.LoanService;
import zw.co.reikan.loans.core.merchant.FindMerchantsResponse;
import zw.co.reikan.loans.core.merchant.MerchantService;
import zw.co.reikan.loans.core.user.*;

import java.security.Principal;
import java.util.List;
import java.util.Set;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;


@Tag(name = "MERCHANTS",
        description = "### Please Note:\n" +
                "1. Auth credentials and endpoint will be provided\n" +
                "2. Please contact  _support@innbucks.co.zw_ for support.\n")
@RestController
@RequestMapping(value = "/api/merchants")
@RequiredArgsConstructor
@Slf4j
public class MerchantController {

    private final MerchantService merchantService;
    private final AuthService authService;
    private final CreateUserService createUserService;
    private final LoanService loanService;
    private final FindUserService findUserService;


    @Operation(summary = "FIND MERCHANTS",
            description = "List all merchants. Account numbers are masked to their last 4 characters for "
                    + "every caller except BULKIT_ADMIN.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @GetMapping
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<FindMerchantsResponse> findAllMerchants(Principal principal) {
        boolean isAdmin = callerGroups(principal).contains(UserGroup.BULKIT_ADMIN);
        FindMerchantsResponse merchants = merchantService.findMerchants(!isAdmin);
        return ResponseEntity.ok(merchants);
    }


    @PostMapping
    @PreAuthorize("hasRole('BULKIT_ADMIN')")
    @Operation(summary = "CREATE MERCHANT",
            description = "Create new merchant. BULKIT_ADMIN only; the disbursement type and account are audited.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Caller is not BULKIT_ADMIN"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<MerchantDto> createMerchant(Principal principal,
                                                      @Valid @RequestBody CreateMerchantRequest createMerchantRequest) {
        MerchantDto merchant = merchantService.createMerchant(createMerchantRequest, principal.getName());
        return ResponseEntity.ok(merchant);
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasRole('BULKIT_ADMIN')")
    @Operation(operationId = "updateMerchant",
            summary = "UPDATE MERCHANT",
            description = "Update an existing merchant's editable details. The merchant code, commission "
                    + "structure and commission group are fixed at creation and cannot be changed. "
                    + "BULKIT_ADMIN only; a change to the disbursement type or account is audited.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields or unknown merchant code"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Caller is not BULKIT_ADMIN"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<MerchantDto> updateMerchant(Principal principal,
                                                      @PathVariable String code,
                                                      @RequestBody UpdateMerchantRequest updateMerchantRequest) {
        MerchantDto merchant = merchantService.updateMerchant(code, updateMerchantRequest, principal.getName());
        return ResponseEntity.ok(merchant);
    }

    @Operation(operationId = "createMerchantAgent",
            summary = "CREATE AGENT",
            description = "Create merchant Agent or User. BULKIT_ADMIN may create any group in any merchant; "
                    + "ORGANISATION_SUPER_USER and RETAIL_SALES may create AGENTS and SUB_AGENTS, and AGENTS may "
                    + "create SUB_AGENTS, in their own merchant only.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @PostMapping({"/{merchantCode}/agents"})
    @PreAuthorize("hasAnyRole('BULKIT_ADMIN','ORGANISATION_SUPER_USER','RETAIL_SALES','AGENTS')")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Group not grantable by the caller, or another merchant"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<SaveUserResponse> createAgent(Principal principal,
                                                        @Valid @RequestBody CreateAgentRequest createUserRequest,
                                                        @PathVariable String merchantCode) {
        Jwt token = ((JwtAuthenticationToken) principal).getToken();
        User loggedInUser = findUserService.resolveUserFromAccessToken(token)
                .orElseThrow(() -> new RuntimeException("Unable to resolve user from token"));

        // Group (body) and merchant (path) are both caller-chosen: the role gate above
        // alone would let any agent mint a CREDIT_MANAGER, or a user in another merchant.
        UserGrantPolicy.checkMayCreate(callerGroups(principal), loggedInUser.getMerchant().getMerchantCode(),
                createUserRequest.getGroup(), merchantCode);

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
                                                                  @Valid @RequestBody CreateAgentRequest createUserRequest,
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
            description = "Create merchant User. Same group and merchant rules as CREATE AGENT.",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @PostMapping({"/{merchantCode}/users"})
    // Needed here too: the call to createAgent below is a self-invocation, which
    // bypasses the method-security proxy and so createAgent's own @PreAuthorize.
    @PreAuthorize("hasAnyRole('BULKIT_ADMIN','ORGANISATION_SUPER_USER','RETAIL_SALES','AGENTS')")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "403", description = "Group not grantable by the caller, or another merchant"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<SaveUserResponse> createUser(Principal principal,
                                                       @Valid @RequestBody CreateAgentRequest createUserRequest,
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
    public ResponseEntity<UserListingResponse> findAgentsForMerchant(Principal principal, @PathVariable String code) {

        Jwt token = ((JwtAuthenticationToken) principal).getToken();

        boolean canViewAllUsers = findUserService.hasAnyRole(token, List.of(UserGroup.ORGANISATION_SUPER_USER.name(),
                UserGroup.RETAIL_SALES.name(), UserGroup.BULKIT_ADMIN.name(), UserGroup.CREDIT_MANAGER.name()));

        List<UserDTO> merchantUsers = authService.findUsersByMerchantCode(code);

        if (canViewAllUsers) {
            return ResponseEntity.ok(UserListingResponse.builder().users(merchantUsers).build());
        }

        User loggedInUser = findUserService.resolveUserFromAccessToken(token)
                .orElseThrow(() -> new RuntimeException("Unable to resolve user from token"));

        return ResponseEntity.ok(UserListingResponse.builder().users(merchantUsers.stream()
                .filter(u -> loggedInUser.getId().equals(u.getAgentId()))
                .toList()).build());
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


    @Operation(operationId = "findMerchantLoans",
            summary = "FIND LOANS",
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

    private static Set<UserGroup> callerGroups(Principal principal) {
        return UserGrantPolicy.callerGroups(((JwtAuthenticationToken) principal).getAuthorities());
    }
}
