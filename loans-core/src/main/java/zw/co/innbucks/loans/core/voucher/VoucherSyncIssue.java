package zw.co.innbucks.loans.core.voucher;

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

import java.time.LocalDateTime;

/**
 * Something a GetMore sync of a voucher could not apply (blocking) or applied with a doubt (a warning), for an
 * operator. One row per voucher, kind and GetMore reference ({@code order:<id>}, {@code creditmemo:<id>},
 * {@code coupon:<id>} or {@code voucher}): seen again, its last-seen time moves; no longer seen by a complete sync, it
 * is cleared; seen after that, it reopens. Its detail never holds a code.
 */
@Entity
@Table(name = "voucher_sync_issues")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class VoucherSyncIssue {

    static final int DETAIL_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voucher_id", nullable = false)
    private Long voucherId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 32, nullable = false)
    private VoucherSyncIssueKind kind;

    @Column(name = "blocking", nullable = false)
    private boolean blocking;

    /** What at GetMore it is about: {@code order:<id>}, {@code creditmemo:<id>}, {@code coupon:<id>} or "voucher". */
    @Column(name = "external_ref", length = 64, nullable = false)
    private String externalRef;

    @Column(name = "detail", length = DETAIL_LENGTH, nullable = false)
    private String detail;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** When a complete sync last stopped seeing it; null while it is open. */
    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    /** Whether it is open: seen by the last sync that could tell. */
    public boolean isOpen() {
        return clearedAt == null;
    }

    /** Seen again: its detail as now, and open again if it had been cleared. */
    void seen(String detail, boolean blocking, LocalDateTime at) {
        this.detail = detail;
        this.blocking = blocking;
        if (at.isAfter(lastSeenAt)) {
            this.lastSeenAt = at;
        }
        this.clearedAt = null;
    }

    /** No longer seen by a complete sync. */
    void clear(LocalDateTime at) {
        if (clearedAt == null) {
            this.clearedAt = at;
        }
    }
}
