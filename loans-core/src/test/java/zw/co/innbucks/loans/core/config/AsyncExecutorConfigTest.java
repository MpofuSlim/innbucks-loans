package zw.co.innbucks.loans.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A full {@code @Async} executor drops the send and counts it: it never throws at the caller (a request, a transaction,
 * the scheduler thread of the paying jobs) and never runs the send on the caller's thread. The executor here is built
 * exactly as Boot builds {@code applicationTaskExecutor}, a {@link ThreadPoolTaskExecutor} the customizer is applied
 * to; {@code AsyncExecutionTest} in loans-api checks Boot really applies it, with the packaged pool sizes.
 */
class AsyncExecutorConfigTest {

    private static ThreadPoolTaskExecutor executor(SimpleMeterRegistry registry, int queueCapacity) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", registry);
        ThreadPoolTaskExecutorCustomizer customizer = new AsyncExecutorConfig()
                .dropAndCountWhenFull(beans.getBeanProvider(MeterRegistry.class));
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        customizer.customize(executor);
        executor.initialize();
        return executor;
    }

    private static double rejected(SimpleMeterRegistry registry) {
        return registry.get(RejectedTasks.METRIC).tag("executor", "applicationTaskExecutor").counter().count();
    }

    @Test
    @DisplayName("the counter exists at 0 as soon as the executor is built")
    void counterRegisteredAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = executor(registry, 1);
        try {
            assertThat(rejected(registry)).isZero();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("a full pool drops the task: no exception for the caller, not run on the caller's thread, counted")
    void fullPoolDropsAndCounts() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = executor(registry, 1);
        CountDownLatch release = new CountDownLatch(1);
        Set<String> ranOn = ConcurrentHashMap.newKeySet();
        AtomicInteger ran = new AtomicInteger();
        try {
            executor.execute(() -> {
                await(release);
                ran.incrementAndGet();
            });
            executor.execute(ran::incrementAndGet);                  // queued: the queue is now full

            String caller = Thread.currentThread().getName();
            assertThatCode(() -> executor.execute(() -> ranOn.add(Thread.currentThread().getName())))
                    .doesNotThrowAnyException();
            assertThatCode(() -> executor.submit(() -> ranOn.add(Thread.currentThread().getName())))
                    .as("the path @Async takes").doesNotThrowAnyException();

            assertThat(ranOn).as("never run on the caller").doesNotContain(caller);
            assertThat(rejected(registry)).isEqualTo(2);

            release.countDown();
            executor.getThreadPoolExecutor().shutdown();          // lets the queued task finish
            assertThat(executor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ran).as("what was accepted still ran").hasValue(2);
            assertThat(ranOn).as("what was dropped never ran").isEmpty();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
