package zw.co.innbucks.loans.core.voucher;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sends vouchers on a thread of their own, never a request's, once the transaction that issued (or re-queued) them
 * commits, so a customer is never sent a voucher that was then rolled back. A voucher left PENDING by a stop is picked up
 * by {@link #requestSweep()}, which the scheduled sweep runs, or sent again on request. One thread, so the gateways are
 * called for one voucher at a time.
 *
 * <p>Deliberately not a Spring {@code Executor} bean, for the reason {@code StaffNotificationDispatcher} gives: one here
 * would become the default {@code @Async} executor for the whole service.
 */
@Slf4j
@Component
public class VoucherDeliveryDispatcher {

    static final String SYSTEM = "system";
    private static final int BATCH = 100;

    private final VoucherRepository voucherRepository;
    private final VoucherDeliverySender sender;
    private final Executor executor;
    private final ExecutorService ownExecutor;

    @Autowired
    public VoucherDeliveryDispatcher(VoucherRepository voucherRepository, VoucherDeliverySender sender) {
        this(voucherRepository, sender, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "voucher-delivery");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** With the executor sends run on; a test passes one that runs them at once. */
    VoucherDeliveryDispatcher(VoucherRepository voucherRepository, VoucherDeliverySender sender, Executor executor) {
        this.voucherRepository = voucherRepository;
        this.sender = sender;
        this.executor = executor;
        this.ownExecutor = executor instanceof ExecutorService service ? service : null;
    }

    /** Sends the voucher once the current transaction commits, or now when there is none. */
    public void afterCommit(Long voucherId, String requestedBy) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(voucherId, requestedBy);
                }
            });
        } else {
            submit(voucherId, requestedBy);
        }
    }

    /** Sends every voucher still PENDING, oldest first, on the dispatcher's own thread. Never throws. */
    public void requestSweep() {
        try {
            executor.execute(this::sendPending);
        } catch (RejectedExecutionException refused) {
            log.error("Pending vouchers could not be queued for sending; they stay PENDING", refused);
        }
    }

    /** Sends every voucher still PENDING, oldest first, on the calling thread; how many there were. */
    int sendPending() {
        int found = 0;
        long after = 0;
        List<Long> batch;
        do {
            // Forward from the last one tried, so one that stays PENDING waits for the next sweep rather than looping.
            batch = voucherRepository.findIdsByDeliveryStatusAfter(VoucherDeliveryStatus.PENDING, after,
                    PageRequest.of(0, BATCH));
            for (Long id : batch) {
                found++;
                after = id;
                try {
                    sender.send(id, SYSTEM);
                } catch (RuntimeException ex) {
                    log.error("Voucher {} could not be sent; it stays as it was", id, ex);
                }
            }
        } while (batch.size() == BATCH);
        return found;
    }

    private void submit(Long voucherId, String requestedBy) {
        try {
            executor.execute(() -> {
                try {
                    sender.send(voucherId, requestedBy);
                } catch (RuntimeException ex) {
                    log.error("Voucher {} could not be sent; it stays as it was", voucherId, ex);
                }
            });
        } catch (RejectedExecutionException refused) {
            log.error("Voucher {} could not be queued for sending; it stays PENDING", voucherId, refused);
        }
    }

    @PreDestroy
    void stop() {
        if (ownExecutor != null) {
            ownExecutor.shutdownNow();
        }
    }
}
