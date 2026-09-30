package zw.co.innbucks.loans.core.draft;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import zw.co.innbucks.loans.core.document.DocumentType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LoanApplicationDraftDocumentRepository extends JpaRepository<LoanApplicationDraftDocument, Long> {

    Optional<LoanApplicationDraftDocument> findByDraftIdAndDocumentType(Long draftId, DocumentType documentType);

    List<LoanApplicationDraftDocument> findByDraftId(Long draftId);

    /** The documents of these drafts, without content. */
    @Query("""
            select new zw.co.innbucks.loans.core.draft.DraftDocumentSummary(d.draftId, d.documentType, d.contentType,
                   d.sizeBytes, d.sha256, d.uploadedAt)
            from LoanApplicationDraftDocument d where d.draftId in :draftIds
            order by d.draftId, d.documentType
            """)
    List<DraftDocumentSummary> findSummaries(@Param("draftIds") Collection<Long> draftIds);

    @Modifying
    @Query("delete from LoanApplicationDraftDocument d where d.draftId = :draftId and d.documentType = :documentType")
    int deleteByDraftIdAndDocumentType(@Param("draftId") Long draftId, @Param("documentType") DocumentType documentType);

    @Modifying
    @Query("delete from LoanApplicationDraftDocument d where d.draftId = :draftId")
    int deleteByDraftId(@Param("draftId") Long draftId);
}
