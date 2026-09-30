package zw.co.innbucks.loans.core.draft;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.document.DocumentType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** One open draft in the caller's list: enough to recognise the applicant and see how far it got. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanApplicationDraftSummary(
        Long id,
        String firstName,
        String lastName,
        String ecNumber,
        String mobileNumber,
        BigDecimal amount,
        Integer tenor,
        int validationErrorCount,
        List<DocumentType> documents,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
