package zw.co.reikan.loans.core.auth;

import zw.co.reikan.loans.core.api.AuthRequest;
import zw.co.reikan.loans.core.api.AuthResponse;
import zw.co.reikan.loans.core.api.SearchUserRequest;
import zw.co.reikan.loans.core.api.UserDTO;
import zw.co.reikan.loans.core.user.User;

import java.util.List;

/**
 * Local authentication and user-account operations. Replaces the former
 * Keycloak-backed service — credentials, profiles and roles now live in this
 * platform's own database and tokens are self-issued.
 */
public interface AuthService {

    AuthResponse login(AuthRequest request);

    User getLoggedInUser();

    String getLoggedInUsername();

    void resetPassword(String newPassword, String userId, String username);

    List<UserDTO> findUsersByMerchantCode(String merchantCode);

    List<UserDTO> search(SearchUserRequest searchUserRequest);

    UserDTO getUser(String userId);

    void deleteUser(String userId);
}
