package zw.co.innbucks.loans.core.voucher;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskDecorator;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.innbucks.loans.core.config.DispatcherExecutor;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sends vouchers on a thread of their own, never a request's, once the transaction that issued (or re-queued) them
 * commits, so a customer is never sent a voucher that was then rolled back. A voucher left PENDING by a stop is picked up
 * by {@link #requestSweep()}, which the scheduled sweep runs, or sent again on request. One thread, so the gateways are
 * called for one voucher at a time.
 *
 * <p>The thread is a {@link DispatcherExecutor}: not a Spring {@code Executor} bean (one here would become the default
 * {@code @Async} executor for the whole service), carrying the trace of the request that issued the voucher, with a
 * bounded queue of {@link #QUEUE_CAPACITY} sends. A send that does not fit is counted
 * ({@code loans.executor.rejected{executor="voucher-delivery"}}) and logged, and its voucher stays PENDING for the
 * sweep or a resend, exactly as when sending fails.
 */
@Slf4j
@Component
public class VoucherDeliveryDispatcher {

    static final String SYSTEM = "system";
    private static final int BATCH = 100;

    /**
     * Large on purpose: the bound is there so a gateway outage cannot fill the heap with queued sends, not to shed
     * ordinary load. Each entry holds a voucher id.
     */
    static final int QUEUE_CAPACITY = 10_000;

    private final VoucherRepository voucherRepository;
    private final VoucherDeliverySender sender;
    private final Executor executor;

    @Autowired
    public VoucherDeliveryDispatcher(VoucherRepository voucherRepository, VoucherDeliverySender sender,
                                     ObjectProvider<TaskDecorator> taskDecorator,
                                     ObjectProvider<MeterRegistry> meterRegistry) {
        this(voucherRepository, sender, new DispatcherExecutor("voucher-delivery", QUEUE_CAPACITY,
                taskDecorator.getIfUnique(), meterRegistry.getIfAvailable()));
    }

    /** With the executor sends run on; a test passes one that runs them at once. */
    VoucherDeliveryDispatcher(VoucherRepository voucherRepository, VoucherDeliverySender sender, Executor executor) {
        this.voucherRepository = voucherRepository;
        this.sender = sender;
        this.executor = executor;
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
        if (executor instanceof DispatcherExecutor own) {
            own.shutdownNow();
        } else if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }
}
