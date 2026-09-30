package zw.co.innbucks.loans.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import zw.co.innbucks.loans.core.instrument.SigningContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The evidence of a signing, as read off the request that submits it (FR-SSB-013). */
class SigningContextsTest {

    private static JwtAuthenticationToken token(List<String> amr) {
        Jwt.Builder jwt = Jwt.withTokenValue("token").header("alg", "HS256").claim("preferred_username", "tmoyo");
        if (amr != null) {
            jwt.claim("amr", amr);
        }
        return new JwtAuthenticationToken(jwt.build());
    }

    @Test
    @DisplayName("the device, address, forwarded chain, client and sign-in method come from the request as sent")
    void readsTheRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.12.34");
        request.addHeader("X-Device-Id", "a3f1c2e4-7b9d-4e21");
        request.addHeader("X-Forwarded-For", "41.190.33.7, 10.0.0.2");
        request.addHeader("User-Agent", "InnBucksPortal/2.4");
        request.addHeader("X-Signer-Authentication", " SUPERAPP_PIN ");

        SigningContext signing = SigningContexts.of(request, token(List.of("pwd")));

        assertThat(signing).isEqualTo(new SigningContext("a3f1c2e4-7b9d-4e21", "10.0.12.34", "41.190.33.7, 10.0.0.2",
                "InnBucksPortal/2.4", "pwd", "SUPERAPP_PIN"));
    }

    @Test
    @DisplayName("a token issued before amr existed was a password sign-in; a blank signer authentication is absent")
    void olderTokensAndBlankHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Signer-Authentication", "  ");

        SigningContext signing = SigningContexts.of(request, token(null));

        assertThat(signing.authenticationMethod()).isEqualTo("pwd");
        assertThat(signing.signerAuthentication()).isNull();
        assertThat(signing.deviceId()).isNull();
        assertThat(SigningContexts.of(request, null).authenticationMethod()).isNull();
    }
}
