package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import zw.co.reikan.loans.core.api.*;
import zw.co.reikan.loans.core.keycloak.KeycloakService;
import zw.co.reikan.loans.core.merchant.FindMerchantsResponse;
import zw.co.reikan.loans.core.merchant.MerchantService;
import zw.co.reikan.loans.core.user.CreateUserService;
import zw.co.reikan.loans.core.user.SaveUserResponse;

import java.util.List;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;


@Tag(name = "MERCHANTS",
        description = "### Please Note:\n" +
                "1. Auth credentials and endpoint will be provided\n" +
                "2. Please contact  _support@bulkit.co.zw_ for support.\n")
@RestController
@RequestMapping(value = "/api/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;
    private final KeycloakService keycloakService;
    private final CreateUserService createUserService;


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
            description = "Create an agent for the merchant",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @PostMapping("/{code}/agents")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<SaveUserResponse> createAgent(@RequestBody CreateAgentRequest createUserRequest, @PathVariable String code) {
        CreateUserResponse createUserResponse = createUserService.create(createUserRequest, code);
        SaveUserResponse saveUserResponse = new SaveUserResponse();
        saveUserResponse.setUser(createUserResponse.user());
        return ResponseEntity.ok(saveUserResponse);
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

}
