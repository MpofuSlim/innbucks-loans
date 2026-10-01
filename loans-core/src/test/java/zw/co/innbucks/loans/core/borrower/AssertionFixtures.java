package zw.co.innbucks.loans.core.borrower;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import zw.co.innbucks.loans.core.config.MarketTimeZone;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/** Keys and assertions for the borrower sign-in tests: the middleware's (RSA), a rotated-out one (EC), and a stranger's. */
final class AssertionFixtures {

    /** Thursday 1 October 2026, 10:00 in Harare. */
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    static final MarketTimeZone ZW = new MarketTimeZone("ZW", Clock.fixed(NOW, ZoneOffset.UTC));

    static final KeyPair MIDDLEWARE = generate("RSA");
    static final KeyPair PREVIOUS_EC = generateEc();
    static final KeyPair STRANGER = generate("RSA");
    static final KeyPair TEST_SIGNER = generate("RSA");

    static final String TEST_API_KEY = "staging-test-assertions-key-0123456789abcdef";

    private AssertionFixtures() {
    }

    static String publicPem(KeyPair pair) {
        return pem("PUBLIC KEY", pair.getPublic().getEncoded());
    }

    static String privatePem(KeyPair pair) {
        return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    /** As an env file carries it: one line, the line breaks written out as {@code \n}. */
    static String oneLine(String pem) {
        return pem.replace("\n", "\\n");
    }

    static BorrowerProperties properties(String publicKey, String previousPublicKey) {
        BorrowerProperties properties = new BorrowerProperties();
        properties.getAssertion().setPublicKey(publicKey);
        properties.getAssertion().setPreviousPublicKey(previousPublicKey);
        return properties;
    }

    static BorrowerProperties withTestAssertions(BorrowerProperties properties) {
        properties.getTestAssertions().setEnabled(true);
        properties.getTestAssertions().setApiKey(TEST_API_KEY);
        properties.getTestAssertions().setPrivateKey(oneLine(privatePem(TEST_SIGNER)));
        return properties;
    }

    /** The claims the middleware sends: Chipo Banda's phone, signed a minute ago with her PIN, good for five minutes. */
    static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .issuer("innbucks-middleware")
                .audience("innbucks-lending")
                .subject("+263773456789")
                .jwtID("mw-7c1d9e2a")
                .issueTime(Date.from(NOW.minusSeconds(60)))
                .expirationTime(Date.from(NOW.plusSeconds(240)))
                .claim("amr", List.of("pin"));
    }

    static String rs256(KeyPair pair, JWTClaimsSet claims) {
        return sign(JWSAlgorithm.RS256, new RSASSASigner(pair.getPrivate()), claims);
    }

    static String es256(KeyPair pair, JWTClaimsSet claims) {
        return sign(JWSAlgorithm.ES256, newEcSigner(pair), claims);
    }

    static String hs256(byte[] secret, JWTClaimsSet claims) {
        try {
            return sign(JWSAlgorithm.HS256, new MACSigner(secret), claims);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWSSigner newEcSigner(KeyPair pair) {
        try {
            return new ECDSASigner((ECPrivateKey) pair.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(JWSAlgorithm algorithm, JWSSigner signer, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private static KeyPair generate(String algorithm) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generateEc() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
