package zw.co.innbucks.loans.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.instrument.SigningContext;

import java.util.List;

/** The evidence of a signing, read from the request that submits the signed application (FR-SSB-013). */
public final class SigningContexts {

    /** The signing device, as the app or portal identifies it. */
    public static final String DEVICE_ID_HEADER = "X-Device-Id";
    /** How the channel authenticated the applicant who signed, as that channel states it. */
    public static final String SIGNER_AUTHENTICATION_HEADER = "X-Signer-Authentication";

    private SigningContexts() {
    }

    public static SigningContext of(HttpServletRequest request, JwtAuthenticationToken authentication) {
        return new SigningContext(
                request.getHeader(DEVICE_ID_HEADER),
                request.getRemoteAddr(),
                request.getHeader("X-Forwarded-For"),
                request.getHeader(HttpHeaders.USER_AGENT),
                authenticationMethod(authentication),
                blankToNull(request.getHeader(SIGNER_AUTHENTICATION_HEADER)));
    }

    /**
     * How the session signed in, from the token's {@code amr}. A token issued before the claim existed was a
     * password sign-in: there has never been another way to get one.
     */
    static String authenticationMethod(JwtAuthenticationToken authentication) {
        if (authentication == null) {
            return null;
        }
        List<String> methods = authentication.getToken().getClaimAsStringList(JwtService.AUTHENTICATION_METHODS_CLAIM);
        return methods == null || methods.isEmpty() ? JwtService.PASSWORD_AUTHENTICATION : String.join(" ", methods);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
