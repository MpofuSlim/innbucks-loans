package zw.co.innbucks.loans.core.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.audit.AuditService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** One file, one fingerprint, whichever way its base64 arrived (FR-SSB-007 matches payslips on it). */
class DocumentFingerprintTest {

    private static final byte[] PAYSLIP = "%PDF-1.7 payslip for August".getBytes(StandardCharsets.UTF_8);
    private static final String RAW = Base64.getEncoder().encodeToString(PAYSLIP);

    @Test
    @DisplayName("the fingerprint is the SHA-256 of the decoded file")
    void fingerprintIsTheSha256OfTheBytes() {
        assertThat(DocumentFingerprint.of(RAW)).isEqualTo(AuditService.sha256Hex(PAYSLIP));
    }

    @Test
    @DisplayName("a data URL, MIME line breaks and stray spaces do not change it")
    void encodingDoesNotChangeIt() {
        String mime = Base64.getMimeEncoder(8, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(PAYSLIP);

        assertThat(DocumentFingerprint.of("data:application/pdf;base64," + RAW)).isEqualTo(DocumentFingerprint.of(RAW));
        assertThat(DocumentFingerprint.of(mime)).isEqualTo(DocumentFingerprint.of(RAW));
        assertThat(DocumentFingerprint.of(" " + RAW + "\n")).isEqualTo(DocumentFingerprint.of(RAW));
    }

    @Test
    @DisplayName("a different file has a different fingerprint")
    void differentFileDiffers() {
        String other = Base64.getEncoder().encodeToString("%PDF-1.7 payslip for July".getBytes(StandardCharsets.UTF_8));

        assertThat(DocumentFingerprint.of(other)).isNotEqualTo(DocumentFingerprint.of(RAW));
    }

    @Test
    @DisplayName("no document, or one that does not decode, has no fingerprint")
    void absentOrUndecodableHasNone() {
        assertThat(DocumentFingerprint.of(null)).isNull();
        assertThat(DocumentFingerprint.of("  ")).isNull();
        assertThat(DocumentFingerprint.of("not*base64!")).isNull();
    }
}
