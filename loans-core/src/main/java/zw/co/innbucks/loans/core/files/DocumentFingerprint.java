package zw.co.innbucks.loans.core.files;

import zw.co.innbucks.loans.core.audit.AuditService;

import java.util.Base64;

/**
 * The SHA-256 of a base64 document's decoded bytes. Hashing the bytes rather than the text means one
 * file has one fingerprint whether it arrived as a {@code data:...;base64,} URL or raw base64, with or
 * without line breaks. It matches only an identical file: a re-saved or re-scanned copy is a different
 * file.
 */
public final class DocumentFingerprint {

    private DocumentFingerprint() {
    }

    /** Null when there is no document, or its payload does not decode. */
    public static String of(String base64Payload) {
        if (base64Payload == null || base64Payload.isBlank()) {
            return null;
        }
        String payload = base64Payload;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        try {
            return AuditService.sha256Hex(Base64.getDecoder().decode(payload.replaceAll("\\s", "")));
        } catch (IllegalArgumentException undecodable) {
            return null;
        }
    }
}
