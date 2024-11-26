package zw.co.reikan.loans.core.keycloak;

import zw.co.reikan.loans.core.api.*;
import zw.co.reikan.loans.core.user.User;

import java.util.List;

public interface KeycloakService {

	AuthResponse login(AuthRequest request);

    User getLoggedInUser();

    void resetPassword(String newPassword, String userId, String username);

	String addUser(CreateUserRequest user, String password);

	List<UserDTO> findUsersByMerchantCode(String partnerId);

	List<UserDTO> search(SearchUserRequest searchUserRequest);


	void deleteUser(String userId);
}
