package zw.co.innbucks.loans.core.document;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Base64;

/**
 * One version of a document with its content, base64 without a data-URL prefix: shown as
 * {@code data:<contentType>;base64,<content>}. Handing one out is logged as a view.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanDocumentContent(
        DocumentType documentType,
        int version,
        DocumentOrigin origin,
        String contentType,
        int sizeBytes,
        String sha256,
        String reason,
        String uploadedBy,
        LocalDateTime uploadedAt,
        String content) {

    public static LoanDocumentContent of(LoanDocument document) {
        return new LoanDocumentContent(document.getDocumentType(), document.getVersion(), document.getOrigin(),
                document.getContentType(), document.getSizeBytes(), document.getSha256(), document.getReason(),
                document.getUploadedBy(), document.getUploadedAt(),
                Base64.getEncoder().encodeToString(document.getContent()));
    }

    /** Everything but the content: a document is never written to a log. */
    @Override
    public String toString() {
        return "LoanDocumentContent[documentType=" + documentType + ", version=" + version + ", contentType="
                + contentType + ", sizeBytes=" + sizeBytes + ", sha256=" + sha256 + "]";
    }
}
