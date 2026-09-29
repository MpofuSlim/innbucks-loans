package zw.co.innbucks.loans.core.auth;

import zw.co.innbucks.loans.core.api.AuthRequest;
import zw.co.innbucks.loans.core.api.AuthResponse;
import zw.co.innbucks.loans.core.api.SearchUserRequest;
import zw.co.innbucks.loans.core.api.UserDto;
import zw.co.innbucks.loans.core.user.User;

import java.util.List;

/**
 * Local authentication and user-account operations: credentials, profiles and
 * roles live in this platform's own database and tokens are self-issued.
 */
public interface AuthService {

    AuthResponse login(AuthRequest request);

    User getLoggedInUser();

    String getLoggedInUsername();

    void resetPassword(String newPassword, String userId, String username);

    List<UserDto> findUsersByMerchantCode(String merchantCode);

    List<UserDto> search(SearchUserRequest searchUserRequest);

    UserDto getUser(String userId);

    void deleteUser(String userId);
}
