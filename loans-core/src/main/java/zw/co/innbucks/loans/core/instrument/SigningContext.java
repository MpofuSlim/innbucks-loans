package zw.co.innbucks.loans.core.instrument;

/**
 * Where and how an application was signed, as the request that submitted it shows (FR-SSB-013).
 *
 * @param deviceId             the signing device, as the app or portal identifies it ({@code X-Device-Id})
 * @param ipAddress            the address the request came from, as this server saw it
 * @param forwardedFor         the {@code X-Forwarded-For} chain as received, when a proxy added one; kept as
 *                             evidence, not trusted, since a client can send it too
 * @param userAgent            the client software, as it described itself
 * @param authenticationMethod how the submitting session signed in to this API ({@code pwd}: password)
 * @param signerAuthentication how the applicant was authenticated by the channel they signed in, as that
 *                             channel states it ({@code X-Signer-Authentication}); absent when not stated
 */
public record SigningContext(
        String deviceId,
        String ipAddress,
        String forwardedFor,
        String userAgent,
        String authenticationMethod,
        String signerAuthentication) {
}
