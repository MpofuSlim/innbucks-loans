package zw.co.innbucks.loans.core.draft;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
import zw.co.innbucks.loans.core.loan.LoanApplicationRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A saved application as it stands (FR-SSB-002).
 *
 * @param application      the fields saved so far, exactly as saved, to resume the form with; the documents
 *                         are listed apart. Absent once submitted, when the loan holds them
 * @param documents        the documents saved with it, without content
 * @param validationErrors each field still missing or invalid, with why, keyed like a refused
 *                         {@code POST /loans}; empty once complete. Submitting also applies the business
 *                         rules (EC number format, age, amount limits, an application already in flight),
 *                         which only a submission can
 * @param complete         nothing is missing or invalid
 * @param loanId           once submitted, the loan it became
 * @param loanReference    once submitted, the loan's reference: the applicant's application reference
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanApplicationDraftResponse(
        Long id,
        LoanApplicationDraftStatus status,
        @Schema(implementation = LoanApplicationRequest.class) JsonNode application,
        List<DraftDocumentSummary> documents,
        Map<String, String> validationErrors,
        Boolean complete,
        Long loanId,
        String loanReference,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime submittedAt) {
}
