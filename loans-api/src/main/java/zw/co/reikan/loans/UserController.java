package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.api.AuthRequest;
import zw.co.reikan.loans.core.api.AuthResponse;
import zw.co.reikan.loans.core.keycloak.KeycloakService;
import zw.co.reikan.loans.core.user.CreateUserService;

import java.security.Principal;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

@Tag(name = "USERS")
@RestController
@RequestMapping(value = "/api/users")
@RequiredArgsConstructor
public class UserController {

    private final KeycloakService keycloakService;

    private final CreateUserService createUserService;


    @Operation(summary = "RESET PASSWORD",
            description = "Reset user password",
            security = {@SecurityRequirement(name = BEARER_TOKEN)}
    )
    @PostMapping(value = "/reset-password")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
            @ApiResponse(responseCode = "500", description = "Processing error")})
    public AuthResponse updatePassword(Principal principal,
                                       @RequestBody ChangePasswordRequest changePasswordRequest) {

        Jwt token = ((JwtAuthenticationToken) principal).getToken();

        keycloakService.login(AuthRequest.builder().username(token.getClaimAsString("preferred_username"))
                .password(changePasswordRequest.getCurrentPassword()).build());

        keycloakService.resetPassword(changePasswordRequest.getNewPassword(), token.getSubject(),
                token.getClaimAsString("preferred_username"));

        return keycloakService.login(AuthRequest.builder()
                .username(token.getClaimAsString("preferred_username"))
                .password(changePasswordRequest.getNewPassword()).build());
    }


//    @GetMapping(path = "/search")
//    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
//            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
//            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
//            @ApiResponse(responseCode = "500", description = "Processing error")})
//
//    public ResponseEntity<UserListingResponse> search(@RequestParam("searchText") String searchText,
//                                                      @RequestParam("pageSize") Integer pageSize, @RequestParam("pageNumber") Integer pageNumber) {
//        SearchUserRequest searchUserRequest = new SearchUserRequest();
//        searchUserRequest.setSearchText(searchText);
//        searchUserRequest.setPageNumber(pageNumber);
//        searchUserRequest.setPageSize(pageSize);
//        List<UserDTO> keycloakUsers = keycloakService.search(searchUserRequest);
//        UserListingResponse userListingResponse = new UserListingResponse();
//        userListingResponse.setMessage("Ok");
//        userListingResponse.setUsers(keycloakUsers);
//        return ResponseEntity.ok(userListingResponse);
//    }
//
//    @PostMapping(path = "/list-all")
//    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
//            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
//            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
//            @ApiResponse(responseCode = "500", description = "Processing error")})
//    public ResponseEntity<ListUsersServiceResponse> listUsers(@RequestBody ListUsersRequest listUsersRequest) {
//        ListUsersResponse response = listUserService.listAll(listUsersRequest);
//        ListUsersServiceResponse listUsersServiceResponse = new ListUsersServiceResponse(
//                response.getUsers().stream().map(UserDTO::fromUser).toList());
//        return ResponseEntity.ok(listUsersServiceResponse);
//    }
//
//    @PostMapping(path = "/report")
//    @ApiResponses({@ApiResponse(responseCode = "200", description = "Success"),
//            @ApiResponse(responseCode = "401", description = "Unauthorized. authentication failed"),
//            @ApiResponse(responseCode = "400", description = "Bad request, missing required fields"),
//            @ApiResponse(responseCode = "500", description = "Processing error")})
//    public ResponseEntity<UserListingResponse> usersReport(@RequestBody UsersReportRequest request) {
//        List<UserDTO> keycloakUsers = keycloakService.findUsersByPartnerId(request.getPartnerId());
//        UserListingResponse userListingResponse = new UserListingResponse();
//        userListingResponse.setMessage("OK");
//        userListingResponse.setUsers(keycloakUsers);
//        return ResponseEntity.ok(userListingResponse);
//    }

}
