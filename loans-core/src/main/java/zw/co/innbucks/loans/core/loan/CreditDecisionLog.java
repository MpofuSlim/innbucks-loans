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

    public CreditDecision record(Loan loan, CreditAction action, String reasonCode, String comment, String performedBy,
                                 LocalDateTime at) {
        return record(loan, action, reasonCode, comment, performedBy, at, null, null);
    }

    /** A referral to a higher credit authority, with where it went and what the officer recommends. */
    public CreditDecision recordReferral(Loan loan, String reasonCode, String comment, String performedBy,
                                         LocalDateTime at, String referredTo, InternalApprovalStatus recommendation) {
        return record(loan, CreditAction.REFERRED, reasonCode, comment, performedBy, at, referredTo, recommendation);
    }

    private CreditDecision record(Loan loan, CreditAction action, String reasonCode, String comment, String performedBy,
                                  LocalDateTime at, String referredTo, InternalApprovalStatus recommendation) {
        String snapshot = CreditDecisionSnapshot.of(loan, currentFingerprints(loan.getId())).toJson();
        return creditDecisionRepository.save(CreditDecision.builder()
                .loanId(loan.getId())
                .action(action)
                .reasonCode(reasonCode)
                .comment(comment)
                .performedBy(performedBy)
                .performedAt(at)
                .referredTo(referredTo)
                .recommendation(recommendation)
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
