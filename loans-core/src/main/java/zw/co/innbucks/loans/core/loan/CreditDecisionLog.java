package zw.co.innbucks.loans.core.loan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.audit.AuditService;

import java.time.LocalDateTime;

/**
 * Writes a loan's credit decision log (FR-PBL-032): one append-only row per credit action, with the loan
 * data it was based on. Runs in the caller's transaction, so the entry commits with the change it records.
 */
@Component
@RequiredArgsConstructor
public class CreditDecisionLog {

    private final CreditDecisionRepository creditDecisionRepository;

    public void record(Loan loan, CreditAction action, String reasonCode, String comment, String performedBy,
                       LocalDateTime at) {
        String snapshot = CreditDecisionSnapshot.of(loan).toJson();
        creditDecisionRepository.save(CreditDecision.builder()
                .loanId(loan.getId())
                .action(action)
                .reasonCode(reasonCode)
                .comment(comment)
                .performedBy(performedBy)
                .performedAt(at)
                .loanSnapshot(snapshot)
                .snapshotSha256(AuditService.sha256Hex(snapshot))
                .build());
    }
}
