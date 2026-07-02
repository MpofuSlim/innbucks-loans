package zw.co.reikan.loans.core.files;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static zw.co.reikan.loans.core.files.FileSignatureValidator.FileKind;

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
        assertThrows(FileSignatureValidator.UnsafeFileException.class,
                () -> validator.requireAcceptedBase64Document("payslipPicture", disguised));
    }

    @Test
    void base64LegitimateDocuments_pass() {
        assertDoesNotThrow(() -> validator.requireAcceptedBase64Document("nationalIdPicture",
                Base64.getEncoder().encodeToString(JPEG)));
        assertDoesNotThrow(() -> validator.requireAcceptedBase64Document("payslipPicture",
                "data:application/pdf;base64," + Base64.getEncoder().encodeToString(PDF)));
    }

    @Test
    void unknownBinaryContent_isRejected() {
        byte[] garbage = {0x00, 0x01, 0x02, 0x03, 0x04};
        assertThrows(FileSignatureValidator.UnsafeFileException.class,
                () -> validator.requireAcceptedBase64Document("payslipPicture",
                        Base64.getEncoder().encodeToString(garbage)));
    }

    @Test
    void absentPayload_isCallerPolicy_notRejectedHere() {
        assertDoesNotThrow(() -> validator.requireAcceptedBase64Document("payslipPicture", null));
        assertDoesNotThrow(() -> validator.requireAcceptedBase64Document("payslipPicture", " "));
    }
}
