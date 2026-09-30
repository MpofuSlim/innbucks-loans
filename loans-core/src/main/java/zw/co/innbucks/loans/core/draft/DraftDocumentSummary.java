package zw.co.innbucks.loans.core.draft;

import com.fasterxml.jackson.annotation.JsonIgnore;
import zw.co.innbucks.loans.core.document.DocumentType;

import java.time.LocalDateTime;

/** A document saved with a draft, without its content. */
public record DraftDocumentSummary(
        @JsonIgnore Long draftId,
        DocumentType documentType,
        String contentType,
        int sizeBytes,
        String sha256,
        LocalDateTime uploadedAt) {
}
