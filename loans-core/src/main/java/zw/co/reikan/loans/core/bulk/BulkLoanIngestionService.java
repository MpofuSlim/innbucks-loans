package zw.co.reikan.loans.core.bulk;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.loan.LoanRequest;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Fault-tolerant chunked bulk-ingestion pipeline (Spring Batch reader→writer
 * pattern, on Java 21 virtual threads).
 *
 * <p><b>Shape:</b> the submitted payload is split into chunks of
 * {@code bulk-ingestion.chunk-size} (default 100). Chunks fan out onto a
 * virtual-thread-per-task executor; inside a chunk, every application runs in
 * its OWN {@code REQUIRES_NEW} transaction via {@link BulkLoanItemProcessor}.</p>
 *
 * <p><b>Blast-radius isolation:</b> a validation failure or a third-party
 * (Ndasenda/InnBucks) crash on item #14 is caught at the item boundary,
 * recorded to {@code audit_logs} with its error context, and the pipeline
 * moves on — healthy loans are never held hostage by a bad neighbour.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulkLoanIngestionService {

    private final BulkIngestionProperties properties;
    private final BulkLoanItemProcessor itemProcessor;
    private final BulkIngestionRunRepository runRepository;
    private final AuditService auditService;

    public record BulkResult(String reference, int total, int succeeded, int failed,
                             List<BulkLoanItemOutcome> outcomes) {}

    public BulkResult ingest(List<LoanRequest> applications, String submittedBy, String channelUsed) {
        if (applications == null || applications.isEmpty()) {
            throw new IllegalArgumentException("Bulk submission contains no applications");
        }
        if (applications.size() > properties.getMaxBatchSize()) {
            throw new IllegalArgumentException("Bulk submission exceeds max batch size of "
                    + properties.getMaxBatchSize());
        }

        String runReference = "BULK-" + UUID.randomUUID();
        BulkIngestionRun run = runRepository.save(BulkIngestionRun.builder()
                .reference(runReference)
                .status(BulkIngestionRun.Status.RUNNING)
                .totalItems(applications.size())
                .submittedBy(submittedBy)
                .channelUsed(channelUsed)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());

        auditService.record(AuditLog.builder()
                .eventType("BULK_RUN_STARTED")
                .entityType("BULK_INGESTION_RUN").entityId(runReference)
                .actorId(submittedBy).channelUsed(channelUsed)
                .detail("items=" + applications.size() + " chunkSize=" + properties.getChunkSize())
                .correlationId(runReference));

        List<List<IndexedRequest>> chunks = chunk(applications);
        List<BulkLoanItemOutcome> outcomes;

        // Virtual threads: cheap enough for one worker per chunk without pool tuning.
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<BulkLoanItemOutcome>>> futures = chunks.stream()
                    .map(chunkItems -> CompletableFuture.supplyAsync(
                            () -> processChunk(runReference, chunkItems, submittedBy, channelUsed), workers))
                    .toList();
            outcomes = futures.stream()
                    .flatMap(f -> f.join().stream())
                    .sorted(Comparator.comparingInt(BulkLoanItemOutcome::index))
                    .toList();
        }

        int succeeded = (int) outcomes.stream().filter(BulkLoanItemOutcome::success).count();
        int failed = outcomes.size() - succeeded;

        run.setStatus(BulkIngestionRun.Status.COMPLETED);
        run.setSucceeded(succeeded);
        run.setFailed(failed);
        run.setCompletedAt(LocalDateTime.now(ZoneOffset.UTC));
        run.setErrorSummary(outcomes.stream()
                .filter(o -> !o.success())
                .map(o -> "idx " + o.index() + ": " + o.error())
                .collect(Collectors.joining("; ")));
        runRepository.save(run);

        auditService.record(AuditLog.builder()
                .eventType("BULK_RUN_COMPLETED")
                .entityType("BULK_INGESTION_RUN").entityId(runReference)
                .actorId(submittedBy).channelUsed(channelUsed)
                .detail("succeeded=" + succeeded + " failed=" + failed)
                .correlationId(runReference));

        log.info("Bulk run {} completed: {} ok / {} failed of {}", runReference, succeeded, failed,
                outcomes.size());
        return new BulkResult(runReference, outcomes.size(), succeeded, failed, outcomes);
    }

    /** One worker group: sequential inside the chunk, isolated per item. */
    private List<BulkLoanItemOutcome> processChunk(String runReference, List<IndexedRequest> chunkItems,
                                                   String submittedBy, String channelUsed) {
        List<BulkLoanItemOutcome> results = new ArrayList<>(chunkItems.size());
        for (IndexedRequest item : chunkItems) {
            try {
                results.add(itemProcessor.process(item.index(), item.request()));
            } catch (Exception ex) {
                // ── Fault isolation: trap, log context, KEEP MOVING ──────────
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                log.error("Bulk run {} item {} rejected: {}", runReference, item.index(), reason);
                auditService.record(AuditLog.builder()
                        .eventType("BULK_ITEM_REJECTED")
                        .entityType("LOAN_APPLICATION").entityId("bulk-item-" + item.index())
                        .actorId(submittedBy).channelUsed(channelUsed)
                        .detail(reason)
                        .payloadHash(AuditService.sha256Hex(String.valueOf(item.request())))
                        .correlationId(runReference));
                results.add(BulkLoanItemOutcome.failed(item.index(), reason));
            }
        }
        return results;
    }

    private List<List<IndexedRequest>> chunk(List<LoanRequest> applications) {
        int chunkSize = Math.max(1, properties.getChunkSize());
        List<List<IndexedRequest>> chunks = new ArrayList<>();
        List<IndexedRequest> current = new ArrayList<>(chunkSize);
        for (int i = 0; i < applications.size(); i++) {
            current.add(new IndexedRequest(i, applications.get(i)));
            if (current.size() == chunkSize) {
                chunks.add(current);
                current = new ArrayList<>(chunkSize);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private record IndexedRequest(int index, LoanRequest request) {}
}
