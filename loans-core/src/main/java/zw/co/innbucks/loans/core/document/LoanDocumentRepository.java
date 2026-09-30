package zw.co.innbucks.loans.core.document;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.innbucks.loans.core.loan.PayslipMatch;

import java.util.List;
import java.util.Optional;

public interface LoanDocumentRepository extends JpaRepository<LoanDocument, Long> {

    /** Every version of every document of the loan, without content, by type then version. */
    @Query("""
            select new zw.co.innbucks.loans.core.document.LoanDocumentSummary(d.documentType, d.version, d.origin,
                   d.contentType, d.sizeBytes, d.sha256, d.reason, d.uploadedBy, d.uploadedAt)
            from LoanDocument d where d.loanId = :loanId
            order by d.documentType, d.version
            """)
    List<LoanDocumentSummary> findSummaries(@Param("loanId") Long loanId);

    Optional<LoanDocument> findFirstByLoanIdAndDocumentTypeOrderByVersionDesc(Long loanId, DocumentType documentType);

    Optional<LoanDocument> findByLoanIdAndDocumentTypeAndVersion(Long loanId, DocumentType documentType, int version);

    boolean existsByLoanIdAndOriginAndUploadedByIgnoreCase(Long loanId, DocumentOrigin origin, String uploadedBy);

    /**
     * The other loans with a payslip of this fingerprint, in ANY version, newest loan first: a payslip that
     * was replaced on one application was still submitted on it (FR-SSB-007).
     */
    @Query("""
            select distinct new zw.co.innbucks.loans.core.loan.PayslipMatch(l.id, l.ecNumber, l.nationalIdNumber)
            from LoanDocument d, Loan l
            where l.id = d.loanId
              and d.documentType = zw.co.innbucks.loans.core.document.DocumentType.PAYSLIP
              and d.sha256 = :sha256
              and l.id <> :excludedLoanId
            order by l.id desc
            """)
    List<PayslipMatch> findPayslipMatches(@Param("sha256") String sha256, @Param("excludedLoanId") Long excludedLoanId,
                                          Pageable page);
}
