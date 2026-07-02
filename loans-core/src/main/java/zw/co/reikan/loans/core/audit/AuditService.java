package zw.co.reikan.loans.core.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
 * Append-only audit writer. Uses REQUIRES_NEW so audit evidence survives even
 * when the surrounding business transaction rolls back (a rejected loan inside
 * a batch must still leave its failure context behind), and never lets an
 * audit-write failure poison the business flow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditLog.AuditLogBuilder builder) {
        try {
            repository.save(builder.createdAt(LocalDateTime.now(ZoneOffset.UTC)).build());
        } catch (Exception e) {
            // Audit must never take down the money path — log loudly instead.
            log.error("AUDIT WRITE FAILED (event dropped to application log only): {}", builder, e);
        }
    }

    public void recordTransition(String entityType, String entityId, String actorId, String channel,
                                 String fromState, String toState, String detail, String correlationId) {
        record(AuditLog.builder()
                .eventType("SAGA_TRANSITION")
                .entityType(entityType)
                .entityId(entityId)
                .actorId(actorId)
                .channelUsed(channel)
                .stateTransitionDelta("{\"from\":\"" + fromState + "\",\"to\":\"" + toState + "\"}")
                .detail(detail)
                .correlationId(correlationId));
    }

    public static String sha256Hex(byte[] payload) {
        if (payload == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(String payload) {
        return payload == null ? null : sha256Hex(payload.getBytes(StandardCharsets.UTF_8));
    }
}
