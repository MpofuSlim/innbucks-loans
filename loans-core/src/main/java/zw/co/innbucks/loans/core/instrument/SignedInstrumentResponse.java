package zw.co.innbucks.loans.core.instrument;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * An instrument as signed, with the evidence of its signing (FR-SSB-013).
 *
 * @param content         the exact text signed
 * @param contentSha256   the SHA-256 of {@code content}
 * @param signatureSha256 the fingerprint of the applicant's signature (the loan's SIGNATURE document)
 * @param signedBy        the user whose session submitted the signed application
 * @param evidenceSha256  the seal over every field of the record
 * @param intact          the text and every field still match their fingerprints and the seal
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignedInstrumentResponse(
        InstrumentType instrumentType,
        int templateVersion,
        String title,
        String content,
        String contentSha256,
        String signatureSha256,
        String witnessSignatureSha256,
        String signedBy,
        LocalDateTime signedAt,
        String deviceId,
        String ipAddress,
        String forwardedFor,
        String userAgent,
        String authenticationMethod,
        String signerAuthentication,
        String evidenceSha256,
        boolean intact) {
}
