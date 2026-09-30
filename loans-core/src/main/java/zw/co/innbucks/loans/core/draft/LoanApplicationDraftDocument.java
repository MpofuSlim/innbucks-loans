package zw.co.innbucks.loans.core.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import zw.co.innbucks.loans.core.document.DocumentType;

import java.time.LocalDateTime;

/**
 * A document saved with a draft, one per type and replaced when saved again. It was checked when saved
 * (FR-SSB-005); on submission it becomes version 1 of the loan's document (FR-SSB-009).
 */
@Entity
@Table(name = "loan_application_draft_documents")
@Getter
@Setter
@ToString(exclude = "content")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanApplicationDraftDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draft_id", nullable = false)
    private Long draftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", length = 32, nullable = false)
    private DocumentType documentType;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "content_type", length = 100, nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;
}
