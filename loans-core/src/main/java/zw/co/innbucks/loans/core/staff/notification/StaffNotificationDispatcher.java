package zw.co.innbucks.loans.core.staff.notification;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Works through the PENDING staff notifications, oldest first, one at a time, at no more than
 * {@code messages-per-second} (FR-GEN-007): the launch broadcast and a weekly run each queue a message for most of the
 * register at once, and the SMS and WhatsApp gateways serve the rest of the fleet too.
 *
 * <p>It runs on a thread of its own, never a request's or a job's, and is woken after the transaction that queued the
 * notifications commits, so a member is never told of an offer that was then rolled back. One pass at a time: a wake-up
 * during a pass makes it go round again rather than starting a second one. A notification left PENDING by a stop is sent
 * by the next pass, which the scheduled sweep or {@code POST /staff-notifications/dispatch} starts.
 *
 * <p>Deliberately not a Spring {@code Executor} bean: Boot creates its default {@code @Async} executor only when the
 * context has none, and one here would quietly put every other {@code @Async} call in the service on this single,
 * paced thread.
 */
@Slf4j
@Component
public class StaffNotificationDispatcher {

    private static final int BATCH = 100;

    private final StaffNotificationRepository notificationRepository;
    private final StaffNotificationSender sender;
    private final StaffNotificationProperties properties;
    private final Executor executor;
    private final ExecutorService ownExecutor;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public StaffNotificationDispatcher(StaffNotificationRepository notificationRepository,
                                       StaffNotificationSender sender,
                                       StaffNotificationProperties properties) {
        this(notificationRepository, sender, properties, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "staff-notifications");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** With the executor passes run on; a test passes one that runs them at once. */
    StaffNotificationDispatcher(StaffNotificationRepository notificationRepository, StaffNotificationSender sender,
                                StaffNotificationProperties properties, Executor executor) {
        this.notificationRepository = notificationRepository;
        this.sender = sender;
        this.properties = properties;
        this.executor = executor;
        this.ownExecutor = executor instanceof ExecutorService service ? service : null;
    }

    /** Starts a pass once the current transaction commits, or now when there is none. */
    public void afterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    requestPass();
                }
            });
        } else {
            requestPass();
        }
    }

    /** Starts a pass, or makes the one under way go round again. Never throws. */
    public void requestPass() {
        requested.set(true);
        startIfIdle();
    }

    /** How many notifications are waiting to be sent. */
    public long pending() {
        return notificationRepository.countByOutboundStatus(StaffNotificationOutboundStatus.PENDING);
    }

    private void startIfIdle() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::passes);
        } catch (RuntimeException refused) {
            running.set(false);
            log.error("Staff notifications could not be queued for sending; they stay PENDING", refused);
        }
    }

    private void passes() {
        try {
            while (requested.getAndSet(false)) {
                pass();
            }
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            log.info("Staff notification sending stopped; what was not sent stays PENDING");
            return;
        } catch (RuntimeException ex) {
            log.error("Staff notification sending stopped on an error; what was not sent stays PENDING", ex);
        } finally {
            running.set(false);
        }
        // A request that arrived as this pass was finishing.
        if (requested.get()) {
            startIfIdle();
        }
    }

    private void pass() throws InterruptedException {
        long pause = properties.getMessagesPerSecond() == 0 ? 0 : 1000L / properties.getMessagesPerSecond();
        int sent = 0;
        int skippedOrTaken = 0;
        long after = 0;
        List<Long> batch;
        do {
            // Forward from the last one tried, so one that stays PENDING after an error waits for the next pass
            // rather than being retried in a loop.
            batch = notificationRepository.findIdsByOutboundStatusAfter(StaffNotificationOutboundStatus.PENDING,
                    after, PageRequest.of(0, BATCH));
            for (Long id : batch) {
                after = id;
                boolean called;
                try {
                    called = sender.send(id);
                } catch (RuntimeException ex) {
                    // Left as it was: PENDING if it broke before the claim, SENDING (never resent) after.
                    log.error("Staff notification {} could not be sent", id, ex);
                    called = true;
                }
                if (called) {
                    sent++;
                    if (pause > 0) {
                        TimeUnit.MILLISECONDS.sleep(pause);
                    }
                } else {
                    skippedOrTaken++;
                }
            }
        } while (batch.size() == BATCH);
        if (sent + skippedOrTaken > 0) {
            log.info("Staff notification pass: {} sent or attempted, {} not sent to a phone", sent, skippedOrTaken);
        }
    }

    @PreDestroy
    void stop() {
        if (ownExecutor != null) {
            ownExecutor.shutdownNow();
        }
    }
}
