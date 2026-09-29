package zw.co.innbucks.loans.core.auth;

import zw.co.innbucks.loans.core.api.LoginRequest;
import zw.co.innbucks.loans.core.api.LoginResponse;
import zw.co.innbucks.loans.core.api.SearchUserRequest;
import zw.co.innbucks.loans.core.api.UserResponse;
import zw.co.innbucks.loans.core.user.User;

import java.util.List;

/**
 * Local authentication and user-account operations: credentials, profiles and
 * roles live in this platform's own database and tokens are self-issued.
 */
public interface AuthService {

    LoginResponse login(LoginRequest request);

    User getLoggedInUser();

    String getLoggedInUsername();

    void resetPassword(String newPassword, String userId, String username);

    List<UserResponse> findUsersByMerchantCode(String merchantCode);

    List<UserResponse> search(SearchUserRequest searchUserRequest);

    UserResponse getUser(String userId);

    void deleteUser(String userId);
}
