package zw.co.innbucks.loans.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The 401 and 403 the security chain writes before any controller runs, in the same envelope as
 * every other error. The bodies are fixed text, so nothing from the failed token reaches the client.
 */
final class SecurityErrorResponses {

    static final String UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or missing token\"}";
    static final String FORBIDDEN_BODY =
            "{\"code\":\"FORBIDDEN\",\"message\":\"Forbidden - insufficient role\"}";
    /** A token minted on a temporary password, used for anything but changing it ({@link TemporaryPasswordFilter}). */
    static final String PASSWORD_CHANGE_REQUIRED_BODY =
            "{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"Change your temporary password before continuing\"}";

    private SecurityErrorResponses() {
    }

    static void unauthorized(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException ex) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY);
    }

    static void forbidden(HttpServletRequest request, HttpServletResponse response,
                          AccessDeniedException ex) throws IOException {
        write(response, HttpStatus.FORBIDDEN, FORBIDDEN_BODY);
    }

    static void passwordChangeRequired(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.FORBIDDEN, PASSWORD_CHANGE_REQUIRED_BODY);
    }

    private static void write(HttpServletResponse response, HttpStatus status, String body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }
}
