package zw.co.innbucks.loans.core.files;

import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.audit.AuditService;

import java.util.Base64;

/**
 * Zero-trust content ingestion: validates uploaded documents by MAGIC-NUMBER
 * byte signature, never by filename extension or declared content type. Blocks
 * embedded executables (Windows PE, ELF, Mach-O, shell/JS scripts) before a
 * payload is accepted or forwarded to object storage.
 */
@Component
public class FileSignatureValidator {

    public enum FileKind {PDF, PNG, JPEG, GIF, UNKNOWN, EXECUTABLE, EMPTY}

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

    /** True when the bytes are one of the accepted KYC document formats. */
    public boolean isAcceptedDocument(byte[] bytes) {
        return switch (classify(bytes)) {
            case PDF, PNG, JPEG, GIF -> true;
            default -> false;
        };
    }

    /**
     * Decodes a whole base64 upload (WhatsApp/Web uploads arrive as base64 data-URLs or raw base64; a
     * {@code data:*;base64,} prefix and line breaks are tolerated) and checks it: an executable is always
     * refused, and a KYC document must also be a PDF, PNG, JPEG or GIF. Null for an absent upload: whether
     * one is required is the caller's decision.
     */
    public DecodedFile decodeBase64Document(String fieldName, String base64Payload, boolean requireDocumentType) {
        if (base64Payload == null || base64Payload.isBlank()) {
            return null;
        }
        String payload = base64Payload;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new UnsafeFileException(fieldName + " is not valid base64 content");
        }
        if (bytes.length == 0) {
            throw new UnsafeFileException(fieldName + " is empty");
        }
        FileKind kind = classify(bytes);
        if (kind == FileKind.EXECUTABLE) {
            throw new UnsafeFileException(fieldName + " contains an executable byte signature — rejected");
        }
        if (requireDocumentType && !isAcceptedDocument(bytes)) {
            throw new UnsafeFileException(fieldName + " is not a recognised document type (PDF/PNG/JPEG/GIF)");
        }
        return new DecodedFile(bytes, contentType(kind), AuditService.sha256Hex(bytes));
    }

    private static String contentType(FileKind kind) {
        return switch (kind) {
            case PDF -> "application/pdf";
            case PNG -> "image/png";
            case JPEG -> "image/jpeg";
            case GIF -> "image/gif";
            default -> "application/octet-stream";
        };
    }

    public static class UnsafeFileException extends RuntimeException {
        public UnsafeFileException(String message) {
            super(message);
        }
    }
}
