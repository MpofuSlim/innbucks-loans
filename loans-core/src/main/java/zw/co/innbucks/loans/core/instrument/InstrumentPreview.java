package zw.co.innbucks.loans.core.instrument;

/**
 * An instrument as the applicant will sign it, filled with their terms, for them to read before they sign.
 *
 * @param version      the version to send back as accepted with the application
 * @param contentSha256 the SHA-256 of {@code content}, as the signed instrument will record it
 */
public record InstrumentPreview(
        InstrumentType instrumentType,
        int version,
        String title,
        String content,
        String contentSha256) {
}
