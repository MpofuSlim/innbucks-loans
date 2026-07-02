package zw.co.reikan.loans.core.bulk;

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
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Bookkeeping row for one chunked bulk-ingestion run. The {@code reference}
 * doubles as the correlation id tying together the run's audit_logs entries.
 */
@Entity
@Table(name = "bulk_ingestion_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkIngestionRun implements Serializable {

    public enum Status {RUNNING, COMPLETED}

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference", length = 64, nullable = false, unique = true)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    @Column(name = "total_items", nullable = false)
    private int totalItems;

    @Column(name = "succeeded", nullable = false)
    private int succeeded;

    @Column(name = "failed", nullable = false)
    private int failed;

    @Column(name = "submitted_by", length = 128)
    private String submittedBy;

    @Column(name = "channel_used", length = 128)
    private String channelUsed;

    /** Compact per-item failure map: "idx 14: Ndasenda timeout; idx 89: bad EC number". */
    @Column(name = "error_summary", columnDefinition = "text")
    private String errorSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
