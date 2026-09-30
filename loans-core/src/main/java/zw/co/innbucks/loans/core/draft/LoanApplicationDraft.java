package zw.co.innbucks.loans.core.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * An application saved part-way, to be completed later (FR-SSB-002). It belongs to the user who started
 * it. {@code application} holds the fields captured so far as JSON, without the documents, which are kept
 * beside it; both are cleared when the draft is submitted and becomes a loan.
 */
@Entity
@Table(name = "loan_application_drafts")
@Getter
@Setter
@ToString(exclude = "application")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanApplicationDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private LoanApplicationDraftStatus status;

    @Column(name = "application", columnDefinition = "text")
    private String application;

    @Column(name = "loan_id")
    private Long loanId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
