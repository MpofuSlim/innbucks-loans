package zw.co.innbucks.loans.core.borrower;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * How Staff Grocery Loan borrowers sign in from the SuperApp (FR-SGL-025, FR-SGL-028). Loans is its own identity
 * provider and never sees the borrower's PIN: the InnBucks middleware checks it and signs a short-lived assertion naming
 * their phone, and loans verifies that with the middleware's PUBLIC key only.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "loans.borrower")
public class BorrowerProperties {

    /** How long a borrower session lasts, in minutes; the app signs in again with a fresh assertion after it. */
    @Min(value = 1, message = "loans.borrower.session-minutes must be at least 1")
    @Max(value = 60, message = "loans.borrower.session-minutes must be at most 60")
    private int sessionMinutes = 15;

    private Assertion assertion = new Assertion();

    private TestAssertions testAssertions = new TestAssertions();

    /** The middleware's assertion: who signs it, for whom, with which key, and how long it may live. */
    @Data
    public static class Assertion {

        @NotBlank(message = "loans.borrower.assertion.issuer is required")
        private String issuer = "innbucks-middleware";

        /** Loans' own audience, so an assertion minted for another service (a fleet login) is not accepted here. */
        @NotBlank(message = "loans.borrower.assertion.audience is required")
        private String audience = "innbucks-lending";

        /** The middleware's public key, PEM or bare base64 of the X.509 encoding. Blank: no assertion is accepted. */
        private String publicKey = "";

        /** The key it signed with before a rotation, accepted alongside the new one until every old assertion expired. */
        private String previousPublicKey = "";

        /** The longest lifetime an assertion may claim ({@code exp - iat}), in seconds. */
        @Min(value = 30, message = "loans.borrower.assertion.max-ttl-seconds must be at least 30")
        @Max(value = 600, message = "loans.borrower.assertion.max-ttl-seconds must be at most 600")
        private long maxTtlSeconds = 300;

        /**
         * How recently the borrower must have entered their PIN or used biometrics for an assertion to approve a loan
         * (FR-SGL-028), in seconds since it was signed.
         */
        @Min(value = 30, message = "loans.borrower.assertion.step-up-max-age-seconds must be at least 30")
        @Max(value = 600, message = "loans.borrower.assertion.step-up-max-age-seconds must be at most 600")
        private long stepUpMaxAgeSeconds = 120;

        /** The {@code amr} values (RFC 8176) that count as a transaction PIN or biometric. */
        @NotEmpty(message = "loans.borrower.assertion.step-up-methods must name at least one method")
        private List<String> stepUpMethods = List.of("pin", "fpt", "face");
    }

    /**
     * Staging only, until the middleware signs assertions: loans signs them itself, for any phone, to whoever holds the
     * api key. Off by default, and never for production: whoever holds the key can borrow as any staff member.
     */
    @Data
    public static class TestAssertions {

        private boolean enabled;

        @ToString.Exclude
        private String apiKey = "";

        /** The RSA private key loans signs test assertions with: PEM or bare base64 of the PKCS#8 encoding. */
        @ToString.Exclude
        private String privateKey = "";
    }
}
