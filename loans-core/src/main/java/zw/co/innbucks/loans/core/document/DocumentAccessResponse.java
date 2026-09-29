package zw.co.innbucks.loans.core.document;

import java.time.LocalDateTime;

/** One entry of a loan's document access log. */
public record DocumentAccessResponse(
        Long id,
        DocumentType documentType,
        int version,
        DocumentAccessAction action,
        String performedBy,
        LocalDateTime performedAt) {

    public static DocumentAccessResponse of(LoanDocumentAccess access) {
        return new DocumentAccessResponse(access.getId(), access.getDocumentType(), access.getVersion(),
                access.getAction(), access.getPerformedBy(), access.getPerformedAt());
    }
}
