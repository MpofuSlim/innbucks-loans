package zw.co.reikan.loans.core.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Stripe-standard idempotency engine.
 *
 * <p>Thread-safety strategy: the {@code idempotency_records} primary key is the
 * single source of truth. {@link #begin} INSERTs first (REQUIRES_NEW so the
 * claim commits immediately, independent of the business transaction); a
 * concurrent duplicate loses the INSERT race with a
 * {@link DataIntegrityViolationException} and is handed the existing record
 * instead. This isolates data streams across threads AND across horizontally
 * scaled API nodes without any in-JVM locking.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;

    /** Outcome of attempting to claim an idempotency key. */
    public sealed interface Claim {
        /** We own the key — execute business logic, then {@link #complete}. */
        record Acquired(IdempotencyRecord record) implements Claim {}
        /** Another in-flight request owns the key — caller should return 409/retry-later. */
        record InProgress(IdempotencyRecord record) implements Claim {}
        /** Key already completed with the same payload — replay the cached response. */
        record Replay(IdempotencyRecord record) implements Claim {}
        /** Key reused with a DIFFERENT payload — client bug, reject (422 semantics). */
        record PayloadMismatch(IdempotencyRecord record) implements Claim {}
    }

    /**
     * Claims {@code key} for this request, or classifies the existing claim.
     * INSERT-first: the DB unique constraint arbitrates concurrent duplicates.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim begin(String key, String channelId, String method, String path, byte[] rawBody, Duration ttl) {
        String requestHash = sha256Hex(method, path, rawBody);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        Optional<IdempotencyRecord> existing = repository.findById(key);
        if (existing.isPresent() && existing.get().isExpired()) {
            // TTL elapsed — the key is free to be reused.
            repository.delete(existing.get());
            repository.flush();
            existing = Optional.empty();
        }
        if (existing.isEmpty()) {
            try {
                IdempotencyRecord record = IdempotencyRecord.builder()
                        .idempotencyKey(key)
                        .status(IdempotencyRecord.Status.IN_PROGRESS)
                        .requestHash(requestHash)
                        .channelId(channelId)
                        .httpMethod(method)
                        .requestPath(path)
                        .createdAt(now)
                        .expiresAt(now.plus(ttl))
                        .build();
                repository.saveAndFlush(record);
                return new Claim.Acquired(record);
            } catch (DataIntegrityViolationException raceLost) {
                log.debug("Idempotency INSERT race lost for key {}, classifying winner's record", key);
                existing = repository.findById(key);
                if (existing.isEmpty()) {
                    // Winner rolled back between our INSERT failure and lookup — treat as in progress.
                    return new Claim.InProgress(IdempotencyRecord.builder().idempotencyKey(key).build());
                }
            }
        }

        IdempotencyRecord record = existing.get();
        if (!record.getRequestHash().equals(requestHash)) {
            return new Claim.PayloadMismatch(record);
        }
        return record.getStatus() == IdempotencyRecord.Status.COMPLETED
                ? new Claim.Replay(record)
                : new Claim.InProgress(record);
    }

    /**
     * Caches the outcome so retries replay the identical response without
     * re-executing business logic (no double disbursement).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(IdempotencyRecord record, int responseStatus, String contentType, String responseBody) {
        record.setStatus(IdempotencyRecord.Status.COMPLETED);
        record.setResponseStatus(responseStatus);
        record.setResponseContentType(contentType);
        record.setResponseBody(responseBody);
        repository.save(record);
    }

    /**
     * Releases the claim after a transport/system (5xx) failure so the client's
     * retry re-executes instead of replaying a transient error forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(IdempotencyRecord record) {
        repository.deleteById(record.getIdempotencyKey());
    }

    @Transactional
    public int purgeExpired() {
        return repository.deleteExpired(LocalDateTime.now(ZoneOffset.UTC));
    }

    static String sha256Hex(String method, String path, byte[] rawBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(rawBody == null ? new byte[0] : rawBody);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
