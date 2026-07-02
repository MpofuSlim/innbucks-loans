package zw.co.reikan.loans.core.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Immutable;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Immutable, append-only audit trail. Every security decision, batch item
 * outcome and saga state transition lands here with enough context to
 * reconstruct <em>who did what, from which channel, and what changed</em>.
 *
 * <p>{@code state_transition_delta} stores a compact {@code from -> to} JSON
 * snapshot; {@code payload_hash} pins the exact request payload (SHA-256)
 * without persisting sensitive content.</p>
 */
@Entity
@Immutable
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_entity", columnList = "entity_type, entity_id"),
        @Index(name = "idx_audit_correlation", columnList = "correlation_id"),
        @Index(name = "idx_audit_created_at", columnList = "created_at")
})
@Getter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. SAGA_TRANSITION, BULK_ITEM_REJECTED, CHANNEL_SIGNATURE_INVALID, VELOCITY_LIMIT_TRIPPED */
    @Column(name = "event_type", length = 64, nullable = false)
    private String eventType;

    @Column(name = "entity_type", length = 64)
    private String entityType;

    @Column(name = "entity_id", length = 64)
    private String entityId;

    /** Authenticated principal (agent/user id) or system job identity. */
    @Column(name = "actor_id", length = 128)
    private String actorId;

    /** Originating channel: WhatsApp gateway, admin portal, mobile app, system job. */
    @Column(name = "channel_used", length = 128)
    private String channelUsed;

    /** Compact JSON: {"from":"CREDIT_APPROVED","to":"DISBURSEMENT_PENDING"} */
    @Column(name = "state_transition_delta", columnDefinition = "text")
    private String stateTransitionDelta;

    /** SHA-256 hex snapshot of the triggering payload. */
    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    /** Ties multi-step flows together (batch run reference, saga id, idempotency key). */
    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
