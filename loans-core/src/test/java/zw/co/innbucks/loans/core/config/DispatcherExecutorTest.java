package zw.co.innbucks.loans.core.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskDecorator;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dispatchers' executor: one daemon thread with the dispatcher's name, a queue that cannot grow without bound, a
 * refusal that is counted (on a series that exists before the first one) and thrown for the dispatcher's own catch,
 * and every task passed through the context's {@link TaskDecorator} (the trace).
 */
class DispatcherExecutorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private DispatcherExecutor executor;

    @AfterEach
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private double rejected(String name) {
        return registry.get(RejectedTasks.METRIC).tag("executor", name).counter().count();
    }

    @Test
    @DisplayName("the refusal counter exists at 0 before anything is refused")
    void counterRegisteredAtZero() {
        executor = new DispatcherExecutor("voucher-delivery", 4, null, registry);

        assertThat(rejected("voucher-delivery")).isZero();
    }

    @Test
    @DisplayName("a full queue refuses the next task with RejectedExecutionException, counted; the queued ones still run")
    void fullQueueRefusesAndCounts() throws Exception {
        executor = new DispatcherExecutor("voucher-delivery", 2, null, registry);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ran = new CountDownLatch(3);
        Runnable blocking = () -> {
            await(release);
            ran.countDown();
        };

        executor.execute(blocking);              // on the thread
        executor.execute(ran::countDown);        // queued
        executor.execute(ran::countDown);        // queued: the queue is now full
        assertThat(executor.queued()).isEqualTo(2);

        assertThatThrownBy(() -> executor.execute(() -> {
        })).isInstanceOf(RejectedExecutionException.class).hasMessageContaining("voucher-delivery");
        assertThat(rejected("voucher-delivery")).isEqualTo(1);

        release.countDown();
        assertThat(ran.await(5, TimeUnit.SECONDS)).as("what was accepted still runs").isTrue();
        assertThat(executor.queued()).isZero();
    }

    @Test
    @DisplayName("after shutdown a task is refused, but that is the service stopping, not a full queue: not counted")
    void refusedAfterShutdownIsNotCounted() {
        executor = new DispatcherExecutor("staff-notifications", 2, null, registry);
        executor.shutdownNow();

        assertThatThrownBy(() -> executor.execute(() -> {
        })).isInstanceOf(RejectedExecutionException.class);
        assertThat(rejected("staff-notifications")).isZero();
    }

    @Test
    @DisplayName("every task goes through the decorator, on one daemon thread named for the dispatcher")
    void decoratesAndNamesTheThread() throws Exception {
        ThreadLocal<String> context = new ThreadLocal<>();
        TaskDecorator carryContext = task -> {
            String captured = context.get();
            return () -> {
                context.set(captured);
                try {
                    task.run();
                } finally {
                    context.remove();
                }
            };
        };
        executor = new DispatcherExecutor("staff-notifications", 2, carryContext, registry);
        CompletableFuture<String> seen = new CompletableFuture<>();

        context.set("trace-of-the-request");
        try {
            executor.execute(() -> seen.complete(context.get() + " on " + Thread.currentThread().getName()
                    + (Thread.currentThread().isDaemon() ? " (daemon)" : "")));
        } finally {
            context.remove();
        }

        assertThat(seen.get(5, TimeUnit.SECONDS)).isEqualTo("trace-of-the-request on staff-notifications (daemon)");
    }

    @Test
    @DisplayName("no meter registry (a context without metrics): still bounded, still counted, never a failure")
    void noRegistry() {
        executor = new DispatcherExecutor("voucher-delivery", 1, null, null);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> await(release));
        executor.execute(() -> {
        });

        assertThatThrownBy(() -> executor.execute(() -> {
        })).isInstanceOf(RejectedExecutionException.class);
        assertThat(executor.rejectedCount()).isEqualTo(1);
        release.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
