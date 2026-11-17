package zw.co.innbucks.loans.core.staff.loan;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.innbucks.loans.core.config.DispatcherExecutor;
import zw.co.innbucks.loans.core.jobs.IdChunks;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one thread cash Staff Grocery Loan payouts are sent on ({@link StaffLoanPayoutService}): never a request's,
 * never the acceptance's, and never the scheduler's. One thread, so at most one booking is in flight in the process.
 *
 * <p>A loan is paid once the transaction that accepted it commits ({@link #afterCommit}), so a payout is never sent for
 * an acceptance that was then rolled back; Finance sends one again ({@link #request}) or every READY one
 * ({@link #requestSweep}). There is no timer and no sweep at boot: a payout left READY by a restart, a full queue or a
 * bank that could not be reached waits for Finance's "Pay all ready".
 *
 * <p>The thread is a {@link DispatcherExecutor}: not a Spring {@code Executor} bean (one here would become the default
 * {@code @Async} executor for the whole service), carrying the trace of the request that queued the payout, with a
 * bounded queue of {@link #QUEUE_CAPACITY} sends. A send that does not fit is counted
 * ({@code loans.executor.rejected{executor="staff-loan-payout"}}) and logged, and its loan stays READY.
 */
@Slf4j
@Component
public class StaffLoanPayoutDispatcher {

    /** Each entry is one loan id, or one pass; a full queue leaves the loan READY, never lost. */
    static final int QUEUE_CAPACITY = 1_000;

    private final StaffLoanPayoutService payouts;
    private final StaffLoanRepository loanRepository;
    private final Executor executor;

    @Autowired
    public StaffLoanPayoutDispatcher(StaffLoanPayoutService payouts, StaffLoanRepository loanRepository,
                                     ObjectProvider<TaskDecorator> taskDecorator,
                                     ObjectProvider<MeterRegistry> meterRegistry) {
        this(payouts, loanRepository, new DispatcherExecutor("staff-loan-payout", QUEUE_CAPACITY,
                taskDecorator.getIfUnique(), meterRegistry.getIfAvailable()));
    }

    /** With the executor payouts run on; a test passes one that runs them at once. */
    StaffLoanPayoutDispatcher(StaffLoanPayoutService payouts, StaffLoanRepository loanRepository, Executor executor) {
        this.payouts = payouts;
        this.loanRepository = loanRepository;
        this.executor = executor;
    }

    /** Pays the loan once the current transaction commits (the acceptance), never before; now when none is active. */
    public void afterCommit(Long staffLoanId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    request(staffLoanId);
                }
            });
        } else {
            request(staffLoanId);
        }
    }

    /**
     * Queues one loan's payout now. Never throws.
     *
     * @return false when the queue had no room: the loan stays READY
     */
    public boolean request(Long staffLoanId) {
        try {
            executor.execute(() -> payOut(staffLoanId));
            return true;
        } catch (RejectedExecutionException refused) {
            log.error("Staff loan {} could not be queued for its payout; it stays READY for Pay all ready",
                    staffLoanId, refused);
            return false;
        }
    }

    /** Queues one pass over every READY cash loan, oldest first. Never throws; false when the queue had no room. */
    public boolean requestSweep() {
        return requestSweep(Set.of());
    }

    /**
     * Queues one pass over every READY cash loan, oldest first, leaving out {@code skipped} (the ones the person who
     * asked must not pay: they are a party to them). Never throws.
     *
     * @return false when the queue had no room: every loan stays READY
     */
    public boolean requestSweep(Collection<Long> skipped) {
        Set<Long> skip = Set.copyOf(skipped);
        try {
            executor.execute(() -> payReady(skip));
            return true;
        } catch (RejectedExecutionException refused) {
            log.error("Ready staff loan payouts could not be queued; they stay READY", refused);
            return false;
        }
    }

    /**
     * Pays every READY cash loan, oldest first, a chunk of ids at a time, on the calling thread (the dispatcher's).
     * Stops at the first payout that did not carry on ({@link StaffLoanPayoutService.Outcome#carriesOn()}): every
     * payout behind it would most likely end the same way. One passed over for a reason of its own (a 403 to that one
     * request, a booking that could not be built) stays READY and the pass goes on; the pass never comes back to it, so
     * it cannot hold every later payout back.
     *
     * @return how many loans it tried to pay
     */
    int payReady(Set<Long> skipped) {
        AtomicInteger tried = new AtomicInteger();
        IdChunks.forEach(loanRepository::findPayoutReadyIds, chunk -> {
            List<Long> due = chunk.stream().filter(id -> !skipped.contains(id)).toList();
            for (int i = 0; i < due.size(); i++) {
                tried.incrementAndGet();
                StaffLoanPayoutService.Outcome outcome = payOut(due.get(i));
                if (outcome == null || !outcome.carriesOn()) {
                    log.warn("Staff loan payout pass stopped at loan {} ({}); {} loan(s) of its chunk, and every"
                            + " later one, left READY", due.get(i), outcome, due.size() - i - 1);
                    return false;
                }
            }
            return true;
        });
        if (tried.get() > 0) {
            log.info("Staff loan payout pass: {} loan(s) tried", tried.get());
        }
        return tried.get();
    }

    private StaffLoanPayoutService.Outcome payOut(Long staffLoanId) {
        try {
            return payouts.payOut(staffLoanId);
        } catch (RuntimeException ex) {
            log.error("Staff loan {}: its payout could not be made; it stays as it was", staffLoanId, ex);
            return null;
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
