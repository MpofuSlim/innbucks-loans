package zw.co.reikan.loans.core.user;

public interface AuthenticationService {

    String getUsernameFromToken(String token);

    AuthResponse generateToken(String email);

    AuthResponse refreshToken(final String authHeader);

}
