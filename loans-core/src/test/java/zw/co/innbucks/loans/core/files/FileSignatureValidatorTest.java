package zw.co.innbucks.loans.core.files;

import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.audit.AuditService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static zw.co.innbucks.loans.core.files.FileSignatureValidator.FileKind;

class FileSignatureValidatorTest {

    private final FileSignatureValidator validator = new FileSignatureValidator();

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] WINDOWS_PE = {'M', 'Z', (byte) 0x90, 0x00};
    private static final byte[] ELF = {0x7F, 'E', 'L', 'F'};
    private static final byte[] SHELL = "#!/bin/sh\nrm -rf /".getBytes(StandardCharsets.UTF_8);

    @Test
    void classifiesDocumentsByMagicNumber() {
        assertEquals(FileKind.PDF, validator.classify(PDF));
        assertEquals(FileKind.PNG, validator.classify(PNG));
        assertEquals(FileKind.JPEG, validator.classify(JPEG));
    }

    @Test
    void classifiesExecutablesRegardlessOfClaimedType() {
        assertEquals(FileKind.EXECUTABLE, validator.classify(WINDOWS_PE));
        assertEquals(FileKind.EXECUTABLE, validator.classify(ELF));
        assertEquals(FileKind.EXECUTABLE, validator.classify(SHELL));
    }

    @Test
    void base64ExecutableWithImageDataUrl_isRejected() {
        // Attacker uploads an EXE renamed to .png inside a data-url — the
        // extension and MIME lie, the magic number does not.
        String disguised = "data:image/png;base64," + Base64.getEncoder().encodeToString(WINDOWS_PE);
        FileSignatureValidator.UnsafeFileException refused = assertThrows(FileSignatureValidator.UnsafeFileException.class,
                () -> validator.decodeBase64Document("signature", disguised, false));
        assertEquals("signature contains an executable byte signature — rejected", refused.getMessage());
    }

    @Test
    void base64LegitimateDocuments_areDecodedWholeWithTypeAndFingerprint() {
        DecodedFile jpeg = validator.decodeBase64Document("nationalIdPicture", Base64.getEncoder().encodeToString(JPEG),
                true);
        assertArrayEquals(JPEG, jpeg.content());
        assertEquals("image/jpeg", jpeg.contentType());
        assertEquals(JPEG.length, jpeg.size());

        // A data-URL prefix and line breaks do not change the file, so they do not change its fingerprint.
        String encoded = Base64.getEncoder().encodeToString(PDF);
        DecodedFile plain = validator.decodeBase64Document("payslipPicture", encoded, true);
        DecodedFile wrapped = validator.decodeBase64Document("payslipPicture",
                "data:application/pdf;base64," + encoded.substring(0, 4) + "\r\n" + encoded.substring(4), true);
        assertEquals("application/pdf", plain.contentType());
        assertEquals(AuditService.sha256Hex(PDF), plain.sha256());
        assertEquals(plain.sha256(), wrapped.sha256());
    }

    @Test
    void unknownBinaryContent_isRejectedAsAKycDocument_butKeptAsASignature() {
        byte[] garbage = {0x00, 0x01, 0x02, 0x03, 0x04};
        String encoded = Base64.getEncoder().encodeToString(garbage);
        FileSignatureValidator.UnsafeFileException refused = assertThrows(FileSignatureValidator.UnsafeFileException.class,
                () -> validator.decodeBase64Document("payslipPicture", encoded, true));
        assertEquals("payslipPicture is not a recognised document type (PDF/PNG/JPEG/GIF)", refused.getMessage());

        assertEquals("application/octet-stream",
                validator.decodeBase64Document("signature", encoded, false).contentType());
    }

    @Test
    void contentThatIsNotBase64_orDecodesToNothing_isRejected() {
        FileSignatureValidator.UnsafeFileException notBase64 = assertThrows(FileSignatureValidator.UnsafeFileException.class,
                () -> validator.decodeBase64Document("signature", "not base64 at all!", false));
        assertEquals("signature is not valid base64 content", notBase64.getMessage());
        FileSignatureValidator.UnsafeFileException empty = assertThrows(FileSignatureValidator.UnsafeFileException.class,
                () -> validator.decodeBase64Document("signature", "data:image/png;base64,", false));
        assertEquals("signature is empty", empty.getMessage());
    }

    @Test
    void absentPayload_isCallerPolicy_notRejectedHere() {
        assertNull(validator.decodeBase64Document("payslipPicture", null, true));
        assertNull(validator.decodeBase64Document("payslipPicture", " ", true));
    }
}
