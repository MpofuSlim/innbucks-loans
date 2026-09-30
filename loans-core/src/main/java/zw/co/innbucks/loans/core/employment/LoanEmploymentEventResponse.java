package zw.co.innbucks.loans.core.employment;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.loan.LoanStage;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * What an employment event did to one loan (FR-SSB-024), with enough of the event and the loan for an officer
 * to act on it from the queue.
 *
 * @param stage     where the loan stands now
 * @param action    NONE, HOLD, DECLINE or REVIEW
 * @param status    OPEN while a hold or review waits for an officer
 * @param outcome   RELEASED, DECLINED or REVIEWED, once settled
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanEmploymentEventResponse(
        Long id,
        Long eventId,
        EmploymentEventType eventType,
        LocalDate effectiveDate,
        Long loanId,
        String loanReference,
        String applicantName,
        LoanStage stage,
        LoanEmploymentEventAction action,
        LoanEmploymentEventStatus status,
        LoanEmploymentEventOutcome outcome,
        String comment,
        String resolvedBy,
        LocalDateTime resolvedAt,
        LocalDateTime createdAt) {
}
