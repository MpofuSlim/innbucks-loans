package zw.co.innbucks.loans.core.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a bounded executor does with a task it has no room for: counts it on {@code loans.executor.rejected{executor}}
 * and says so in the log, at most once every {@link #WARN_EVERY_NANOS ten seconds} with the count since the last line.
 * The counter is registered at 0 when the executor is built, so a first drop shows as an increase rather than as a
 * series appearing. The log line names the executor and its load, never the task: a task carries a phone number, a
 * message or a voucher id.
 */
@Slf4j
public final class RejectedTasks {

    public static final String METRIC = "loans.executor.rejected";

    static final long WARN_EVERY_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final String executor;
    private final Counter counter;
    private final AtomicLong sinceLastWarning = new AtomicLong();
    private final AtomicLong lastWarning = new AtomicLong(System.nanoTime() - WARN_EVERY_NANOS);

    /**
     * @param registry where the counter goes; none (a context without metrics, a unit test) counts into a registry of
     *                 its own
     */
    public RejectedTasks(String executor, MeterRegistry registry) {
        this.executor = executor;
        this.counter = Counter.builder(METRIC)
                .description("Tasks a bounded executor had no room for, and so never ran")
                .tag("executor", executor)
                .register(registry == null ? new SimpleMeterRegistry() : registry);
    }

    /**
     * Counts one task that will not run, and warns unless it warned in the last ten seconds.
     *
     * @param consequence what not running it means, for the log: e.g. "the message is not sent"
     */
    public void record(int queued, int capacity, String consequence) {
        counter.increment();
        long dropped = sinceLastWarning.incrementAndGet();
        long now = System.nanoTime();
        long last = lastWarning.get();
        if (now - last >= WARN_EVERY_NANOS && lastWarning.compareAndSet(last, now)) {
            sinceLastWarning.addAndGet(-dropped);
            log.warn("{} is full ({} queued, capacity {}): {} task(s) not run since the last warning; {}",
                    executor, queued, capacity, dropped, consequence);
        }
    }

    public double count() {
        return counter.count();
    }
}
