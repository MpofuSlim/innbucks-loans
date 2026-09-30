package zw.co.innbucks.loans.core.draft;

import zw.co.innbucks.loans.core.document.DocumentType;

import java.time.LocalDateTime;
import java.util.Base64;

/** A document saved with a draft, with its content as base64: shown as {@code data:<contentType>;base64,<content>}. */
public record DraftDocumentContent(
        DocumentType documentType,
        String contentType,
        int sizeBytes,
        String sha256,
        LocalDateTime uploadedAt,
        String content) {

    static DraftDocumentContent of(LoanApplicationDraftDocument document) {
        return new DraftDocumentContent(document.getDocumentType(), document.getContentType(), document.getSizeBytes(),
                document.getSha256(), document.getUploadedAt(), Base64.getEncoder().encodeToString(document.getContent()));
    }

    /** Everything but the content: a document is never written to a log. */
    @Override
    public String toString() {
        return "DraftDocumentContent[documentType=" + documentType + ", contentType=" + contentType + ", sizeBytes="
                + sizeBytes + ", sha256=" + sha256 + "]";
    }
}
