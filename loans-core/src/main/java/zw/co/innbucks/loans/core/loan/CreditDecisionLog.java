package zw.co.innbucks.loans.core.loan;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.document.DocumentType;
import zw.co.innbucks.loans.core.document.LoanDocumentRepository;
import zw.co.innbucks.loans.core.document.LoanDocumentSummary;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * Writes a loan's credit decision log (FR-PBL-032): one append-only row per credit action, with the loan
 * data it was based on. Runs in the caller's transaction, so the entry commits with the change it records.
 */
@Component
@RequiredArgsConstructor
public class CreditDecisionLog {

    private final CreditDecisionRepository creditDecisionRepository;
    private final LoanDocumentRepository loanDocumentRepository;

    public void record(Loan loan, CreditAction action, String reasonCode, String comment, String performedBy,
                       LocalDateTime at) {
        String snapshot = CreditDecisionSnapshot.of(loan, currentFingerprints(loan.getId())).toJson();
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

    /** Read straight from the documents: the version a decision was made on is the one on file at that moment. */
    private Map<DocumentType, String> currentFingerprints(Long loanId) {
        Map<DocumentType, String> fingerprints = new EnumMap<>(DocumentType.class);
        if (loanId != null) {
            LoanDocumentSummary.currentOf(loanDocumentRepository.findSummaries(loanId))
                    .forEach((type, summary) -> fingerprints.put(type, summary.sha256()));
        }
        return fingerprints;
    }
}
