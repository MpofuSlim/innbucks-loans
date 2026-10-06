package zw.co.innbucks.loans.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.task.TaskDecorator;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The one-thread executor a dispatcher ({@code VoucherDeliveryDispatcher}, {@code StaffNotificationDispatcher}) sends
 * on. It replaced {@code Executors.newSingleThreadExecutor()}, whose queue had no bound. Three things it adds:
 * <ul>
 *   <li><b>A bounded queue.</b> A task that does not fit is refused with {@link RejectedExecutionException}, which the
 *       dispatcher already catches and logs, and is counted ({@link RejectedTasks},
 *       {@code loans.executor.rejected{executor}}). Nothing is lost by it: what a dispatcher sends is a PENDING row,
 *       which the next sweep or pass sends.</li>
 *   <li><b>The trace.</b> Every task goes through the context's {@link TaskDecorator}, the one Boot applies to the
 *       {@code @Async} executor ({@code TracingConfig.traceContextTaskDecorator()}: the observation only, never the
 *       caller's authentication), so the dispatcher's log lines carry the trace of the request that queued them.</li>
 *   <li>A daemon thread with the dispatcher's name.</li>
 * </ul>
 * Deliberately not a Spring {@code Executor} bean: Boot creates its {@code @Async} executor only when the context has
 * none, so one here would quietly move every {@code @Async} call onto this single thread.
 */
public final class DispatcherExecutor implements Executor {

    private final String name;
    private final int capacity;
    private final TaskDecorator decorator;
    private final RejectedTasks rejected;
    private final ThreadPoolExecutor pool;

    /**
     * @param decorator the context's task decorator, or none
     * @param registry  where refusals are counted, or none
     */
    public DispatcherExecutor(String name, int capacity, TaskDecorator decorator, MeterRegistry registry) {
        this.name = name;
        this.capacity = capacity;
        this.decorator = decorator;
        this.rejected = new RejectedTasks(name, registry);
        this.pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                },
                (task, executor) -> {
                    if (!executor.isShutdown()) {
                        rejected.record(executor.getQueue().size(), capacity, "what it was to send stays PENDING");
                    }
                    throw new RejectedExecutionException(name + " has no room for another task");
                });
    }

    @Override
    public void execute(Runnable task) {
        pool.execute(decorator == null ? task : decorator.decorate(task));
    }

    /** Stops the thread; a task still queued is not run, and what it was to send stays PENDING. */
    public void shutdownNow() {
        pool.shutdownNow();
    }

    public String name() {
        return name;
    }

    public int capacity() {
        return capacity;
    }

    int queued() {
        return pool.getQueue().size();
    }

    double rejectedCount() {
        return rejected.count();
    }
}
