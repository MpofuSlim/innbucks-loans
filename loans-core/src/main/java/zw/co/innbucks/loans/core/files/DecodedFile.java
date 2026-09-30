package zw.co.innbucks.loans.core.files;

/**
 * An uploaded file, decoded from its base64 and checked: its bytes, the content type read from its own
 * byte signature, and its fingerprint (the SHA-256 of the bytes). One file has one fingerprint however
 * its base64 was sent; a re-saved or re-scanned copy is a different file.
 */
public record DecodedFile(byte[] content, String contentType, String sha256) {

    public int size() {
        return content.length;
    }
}
