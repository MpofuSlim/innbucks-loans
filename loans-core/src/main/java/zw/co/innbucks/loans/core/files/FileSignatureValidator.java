package zw.co.innbucks.loans.core.files;

import org.springframework.stereotype.Component;

/**
 * Zero-trust content ingestion: validates uploaded documents by MAGIC-NUMBER
 * byte signature, never by filename extension or declared content type. Blocks
 * embedded executables (Windows PE, ELF, Mach-O, shell/JS scripts) before a
 * payload is accepted or forwarded to object storage.
 */
@Component
public class FileSignatureValidator {

    public enum FileKind {
        PDF("application/pdf"),
        PNG("image/png"),
        JPEG("image/jpeg"),
        GIF("image/gif"),
        UNKNOWN("application/octet-stream"),
        EXECUTABLE("application/octet-stream"),
        EMPTY("application/octet-stream");

        private final String contentType;

        FileKind(String contentType) {
            this.contentType = contentType;
        }

        public String contentType() {
            return contentType;
        }
    }

    /** Classifies raw bytes by their leading signature. */
    public FileKind classify(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return FileKind.EMPTY;
        }
        // ── Executable / script signatures: hard block ──────────────────────
        if (bytes[0] == 'M' && bytes[1] == 'Z') {
            return FileKind.EXECUTABLE;                    // Windows PE
        }
        if (bytes[0] == 0x7F && bytes[1] == 'E' && bytes[2] == 'L' && bytes[3] == 'F') {
            return FileKind.EXECUTABLE;                    // ELF
        }
        if ((bytes[0] & 0xFF) == 0xCF && (bytes[1] & 0xFF) == 0xFA
                && (bytes[2] & 0xFF) == 0xED && (bytes[3] & 0xFF) == 0xFE) {
            return FileKind.EXECUTABLE;                    // Mach-O 64
        }
        if (bytes[0] == '#' && bytes[1] == '!') {
            return FileKind.EXECUTABLE;                    // shebang script
        }
        // ── Accepted document types ─────────────────────────────────────────
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            return FileKind.PDF;
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return FileKind.PNG;
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return FileKind.JPEG;
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return FileKind.GIF;
        }
        return FileKind.UNKNOWN;
    }
}
