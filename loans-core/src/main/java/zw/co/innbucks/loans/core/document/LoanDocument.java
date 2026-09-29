package zw.co.innbucks.loans.core.document;

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
import lombok.ToString;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * One version of one document of one loan (FR-SSB-009). Never changed once written: a replacement is a
 * new version, and the database refuses UPDATE, DELETE and TRUNCATE on the table.
 */
@Entity
@Immutable
@Table(name = "loan_documents")
@Getter
@ToString(exclude = "content")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_id", nullable = false)
    private Long loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", length = 32, nullable = false)
    private DocumentType documentType;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", length = 32, nullable = false)
    private DocumentOrigin origin;

    /** The decoded file. */
    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "content_type", length = 100, nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    /** Why it was replaced; null for a document that came with the application. */
    @Column(name = "reason")
    private String reason;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;
}
