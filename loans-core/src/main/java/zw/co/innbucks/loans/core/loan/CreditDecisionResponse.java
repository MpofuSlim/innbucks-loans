package zw.co.innbucks.loans.core.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;

import java.time.LocalDateTime;

/**
 * One entry of a loan's credit decision log. {@code loanSnapshot} is the stored JSON, emitted as is, and
 * {@code snapshotSha256} its hash, so a reader can check the one against the other.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreditDecisionResponse(
        Long id,
        CreditAction action,
        String reasonCode,
        String reasonDescription,
        String comment,
        String performedBy,
        LocalDateTime performedAt,
        @JsonRawValue String loanSnapshot,
        String snapshotSha256) {

    public static CreditDecisionResponse of(CreditDecision entry, String reasonDescription) {
        return new CreditDecisionResponse(entry.getId(), entry.getAction(), entry.getReasonCode(), reasonDescription,
                entry.getComment(), entry.getPerformedBy(), entry.getPerformedAt(), entry.getLoanSnapshot(),
                entry.getSnapshotSha256());
    }
}
