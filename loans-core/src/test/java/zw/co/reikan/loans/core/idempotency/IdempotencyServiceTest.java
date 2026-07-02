package zw.co.reikan.loans.core.idempotency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import zw.co.reikan.loans.core.idempotency.IdempotencyService.Claim;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String KEY = "0e8dbe0e-6aa3-4f7e-9d3e-1c2b3a4d5e6f";
    private static final byte[] BODY = "{\"amount\":100}".getBytes(StandardCharsets.UTF_8);
    private static final Duration TTL = Duration.ofHours(24);

    @Mock
    private IdempotencyRecordRepository repository;

    private IdempotencyService service() {
        return new IdempotencyService(repository);
    }

    private IdempotencyRecord existing(IdempotencyRecord.Status status, String hash) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return IdempotencyRecord.builder()
                .idempotencyKey(KEY).status(status).requestHash(hash)
                .createdAt(now).expiresAt(now.plusHours(1))
                .responseStatus(200).responseBody("{\"ok\":true}")
                .build();
    }

    @Test
    void firstCall_acquiresTheKey() {
        when(repository.findById(KEY)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Claim claim = service().begin(KEY, "ch", "POST", "/api/loans", BODY, TTL);

        assertInstanceOf(Claim.Acquired.class, claim);
    }

    @Test
    void completedKeyWithSamePayload_replaysCachedResponse() {
        String hash = IdempotencyService.sha256Hex("POST", "/api/loans", BODY);
        when(repository.findById(KEY))
                .thenReturn(Optional.of(existing(IdempotencyRecord.Status.COMPLETED, hash)));

        Claim claim = service().begin(KEY, "ch", "POST", "/api/loans", BODY, TTL);

        assertInstanceOf(Claim.Replay.class, claim);
    }

    @Test
    void completedKeyWithDifferentPayload_isPayloadMismatch() {
        String hash = IdempotencyService.sha256Hex("POST", "/api/loans", BODY);
        when(repository.findById(KEY))
                .thenReturn(Optional.of(existing(IdempotencyRecord.Status.COMPLETED, hash)));

        byte[] otherBody = "{\"amount\":999}".getBytes(StandardCharsets.UTF_8);
        Claim claim = service().begin(KEY, "ch", "POST", "/api/loans", otherBody, TTL);

        assertInstanceOf(Claim.PayloadMismatch.class, claim);
    }

    @Test
    void inFlightKey_reportsInProgress() {
        String hash = IdempotencyService.sha256Hex("POST", "/api/loans", BODY);
        when(repository.findById(KEY))
                .thenReturn(Optional.of(existing(IdempotencyRecord.Status.IN_PROGRESS, hash)));

        Claim claim = service().begin(KEY, "ch", "POST", "/api/loans", BODY, TTL);

        assertInstanceOf(Claim.InProgress.class, claim);
    }

    @Test
    void insertRaceLoser_isClassifiedFromWinnersRecord() {
        String hash = IdempotencyService.sha256Hex("POST", "/api/loans", BODY);
        // findById first sees nothing; INSERT collides; second lookup sees the winner.
        when(repository.findById(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing(IdempotencyRecord.Status.IN_PROGRESS, hash)));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        Claim claim = service().begin(KEY, "ch", "POST", "/api/loans", BODY, TTL);

        assertInstanceOf(Claim.InProgress.class, claim);
    }

    @Test
    void requestHash_bindsMethodPathAndBody() {
        String base = IdempotencyService.sha256Hex("POST", "/api/loans", BODY);
        assertNotEquals(base, IdempotencyService.sha256Hex("PUT", "/api/loans", BODY));
        assertNotEquals(base, IdempotencyService.sha256Hex("POST", "/api/loans/bulk", BODY));
        assertNotEquals(base, IdempotencyService.sha256Hex("POST", "/api/loans", new byte[0]));
    }
}
