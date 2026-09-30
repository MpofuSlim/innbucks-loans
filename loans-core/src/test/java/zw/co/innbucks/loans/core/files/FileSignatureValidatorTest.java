package zw.co.innbucks.loans.core.files;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void eachAcceptedKindCarriesItsContentType() {
        assertEquals("application/pdf", FileKind.PDF.contentType());
        assertEquals("image/png", FileKind.PNG.contentType());
        assertEquals("image/jpeg", FileKind.JPEG.contentType());
        assertEquals("image/gif", FileKind.GIF.contentType());
    }

    @Test
    void unknownAndTooShortContentAreNotDocuments() {
        assertEquals(FileKind.UNKNOWN, validator.classify(new byte[]{0x00, 0x01, 0x02, 0x03, 0x04}));
        assertEquals(FileKind.EMPTY, validator.classify(new byte[]{'%', 'P'}));
        assertEquals(FileKind.EMPTY, validator.classify(null));
    }
}
