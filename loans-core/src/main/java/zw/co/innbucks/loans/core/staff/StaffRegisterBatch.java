package zw.co.innbucks.loans.core.staff;

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

import java.time.LocalDateTime;

/**
 * A submission to the staff register (FR-SGL-002, FR-SGL-004): an uploaded file or one record changed on the admin
 * screen. Submitted by one person and approved or rejected by another; the database refuses a decision by the
 * submitter. Nothing reaches the register until it is approved.
 */
@Entity
@Table(name = "staff_register_batches")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StaffRegisterBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    private StaffRegisterBatchSource source;

    @Column(name = "file_name")
    private String fileName;

    /** SHA-256 of the uploaded file as received, so the exact file can be identified later. */
    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private StaffRegisterBatchStatus status;

    @Column(name = "submitted_by", nullable = false)
    private String submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "submission_comment")
    private String submissionComment;

    @Column(name = "total_rows", nullable = false)
    private Integer totalRows;

    @Column(name = "staged_rows", nullable = false)
    private Integer stagedRows;

    @Column(name = "rejected_rows", nullable = false)
    private Integer rejectedRows;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_comment")
    private String decisionComment;

    /** On approval: how its staged rows were applied. */
    @Column(name = "created_rows")
    private Integer createdRows;

    @Column(name = "amended_rows")
    private Integer amendedRows;

    @Column(name = "unchanged_rows")
    private Integer unchangedRows;

    @Column(name = "skipped_rows")
    private Integer skippedRows;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
