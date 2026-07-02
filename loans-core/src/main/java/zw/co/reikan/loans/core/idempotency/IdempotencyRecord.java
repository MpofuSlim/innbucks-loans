package zw.co.reikan.loans.core.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Durable record of a mutating API call keyed by the client-supplied
 * {@code Idempotency-Key} header (Stripe-style idempotency engine).
 *
 * <p>The primary-key constraint on {@code idempotency_key} is the arbiter of
 * concurrent duplicates: whichever request INSERTs first owns execution; every
 * racer observes the row and either waits out an {@code IN_PROGRESS} record or
 * replays the {@code COMPLETED} response verbatim. This works across JVMs —
 * no distributed lock required.</p>
 *
 * <p>{@code request_hash} pins the key to one payload: reusing a key with a
 * different body is a client bug and is rejected (422 semantics) rather than
 * silently replaying an unrelated response.</p>
 */
@Entity
@Table(name = "idempotency_records", indexes = {
        @Index(name = "idx_idempotency_expires_at", columnList = "expires_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyRecord implements Serializable {

    public enum Status {IN_PROGRESS, COMPLETED}

    /** Client-supplied UUIDv4 — primary key, so duplicates collide at the DB. */
    @Id
    @Column(name = "idempotency_key", length = 64, nullable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status;

    /** SHA-256 hex of method + path + raw request body — detects key reuse with a different payload. */
    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "channel_id", length = 128)
    private String channelId;

    @Column(name = "http_method", length = 8)
    private String httpMethod;

    @Column(name = "request_path", length = 256)
    private String requestPath;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_content_type", length = 128)
    private String responseContentType;

    /** Cached response body replayed verbatim to retried calls. */
    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** TTL boundary (24–48 h). Purged by {@code IdempotencyPurgeJob}; expired rows are treated as absent. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now(ZoneOffset.UTC));
    }
}
