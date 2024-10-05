package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

@RestController
@RequestMapping(value = "/api/merchants")
@RequiredArgsConstructor
public class MerchantController {

    private final MerchantService merchantService;
    private final KeycloakService keycloakService;
    private final CreateUserService createUserService;

    @PostMapping
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<MerchantDto> createMerchant(@RequestBody CreateMerchantRequest createMerchantRequest) {
        MerchantDto merchant = merchantService.createMerchant(createMerchantRequest);
        return ResponseEntity.ok(merchant);
    }

    @GetMapping
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<FindMerchantsResponse> findAllMerchants() {
        FindMerchantsResponse merchants = merchantService.findMerchants();
        return ResponseEntity.ok(merchants);
    }

    @GetMapping("/{code}/agents")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public ResponseEntity<UserListingResponse> findAgentsForMerchant(@PathVariable String code) {
        List<UserDTO> keycloakUsers = keycloakService.findUsersByMerchantCode(code);
        return ResponseEntity.ok(UserListingResponse.builder().users(keycloakUsers).build());
    }

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


}
