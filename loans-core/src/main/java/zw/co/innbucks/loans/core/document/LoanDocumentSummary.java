package zw.co.innbucks.loans.core.document;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/** One version of a document, without its content. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanDocumentSummary(
        DocumentType documentType,
        int version,
        DocumentOrigin origin,
        String contentType,
        int sizeBytes,
        String sha256,
        String reason,
        String uploadedBy,
        LocalDateTime uploadedAt) {

    public static LoanDocumentSummary of(LoanDocument document) {
        return new LoanDocumentSummary(document.getDocumentType(), document.getVersion(), document.getOrigin(),
                document.getContentType(), document.getSizeBytes(), document.getSha256(), document.getReason(),
                document.getUploadedBy(), document.getUploadedAt());
    }

    /** The highest version of each document type among these. */
    public static Map<DocumentType, LoanDocumentSummary> currentOf(Collection<LoanDocumentSummary> versions) {
        Map<DocumentType, LoanDocumentSummary> current = new EnumMap<>(DocumentType.class);
        for (LoanDocumentSummary summary : versions) {
            current.merge(summary.documentType(), summary, (a, b) -> a.version() >= b.version() ? a : b);
        }
        return current;
    }
}
