package zw.co.innbucks.loans.core.employment;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * An employment event (FR-SSB-024) and what it did to each of the borrower's loans it found open.
 *
 * @param loans one entry per loan the event found open; empty when the borrower had none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmploymentEventResponse(
        Long id,
        String ecNumber,
        EmploymentEventType eventType,
        LocalDate effectiveDate,
        LocalDate endDate,
        String ministry,
        String station,
        String grade,
        String note,
        String recordedBy,
        LocalDateTime recordedAt,
        List<LoanEmploymentEventResponse> loans) {
}
