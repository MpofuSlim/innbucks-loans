package zw.co.innbucks.loans.core.borrower;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Keys as an environment variable can carry them: PEM with real line breaks, PEM with {@code \n} written out (an env
 * file holds one line per key), or the bare base64 body.
 */
final class PemKeys {

    private PemKeys() {
    }

    /** An RSA or EC public key from its X.509 encoding. */
    static PublicKey publicKey(String text, String name) {
        byte[] der = decode(text, name);
        for (String algorithm : new String[]{"RSA", "EC"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
            } catch (GeneralSecurityException notThisOne) {
                // try the next family
            }
        }
        throw new IllegalStateException(name + " is not an RSA or EC public key");
    }

    /** An RSA private key from its PKCS#8 encoding. */
    static PrivateKey rsaPrivateKey(String text, String name) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decode(text, name)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(name + " is not an RSA private key in PKCS#8", e);
        }
    }

    /** The public half of an RSA private key. */
    static PublicKey publicHalf(PrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalStateException("The private key does not carry its public exponent");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA unavailable", e);
        }
    }

    private static byte[] decode(String text, String name) {
        String body = text.replace("\\n", "\n")
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " is not base64", e);
        }
    }
}
