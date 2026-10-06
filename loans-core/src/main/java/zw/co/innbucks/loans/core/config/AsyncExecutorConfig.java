package zw.co.innbucks.loans.core.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.task.ThreadPoolTaskExecutorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * What the {@code @Async} executor does when it is full. Loans declares no executor of its own: {@code @Async} runs on
 * Boot's {@code applicationTaskExecutor}, whose queue and pool are bounded in {@code application.yml}
 * ({@code spring.task.execution.pool}). Boot's own answer to a full pool is to THROW at the caller, and the usual
 * alternative runs the task ON the caller's thread; both are wrong here. Every {@code @Async} method in loans sends a
 * message (an SMS, an email, a WhatsApp, a loan notice), and the callers are requests, transactions and the one
 * scheduler thread that drives the paying jobs:
 * <ul>
 *   <li>throwing would fail, or roll back, the work the message is about — a booking already made, a user already
 *       created — and {@code LoanNotificationSender} promises it never throws, because the stage it reports has
 *       happened;</li>
 *   <li>running it on the caller would hold a request, a database transaction or the scheduler for a gateway's
 *       timeout, which is what {@code @Async} is there to prevent.</li>
 * </ul>
 * So a send with no room is DROPPED and counted on {@code loans.executor.rejected{executor="applicationTaskExecutor"}}
 * (registered at 0; alert on any increase), with a throttled WARN that names no recipient. The bound is large (see
 * {@code application.yml}) because nothing re-sends these messages: it exists so an outage of a gateway cannot fill the
 * heap with pending sends, not to shed ordinary load.
 */
@Configuration(proxyBeanMethods = false)
public class AsyncExecutorConfig {

    static final String EXECUTOR = "applicationTaskExecutor";

    @Bean
    ThreadPoolTaskExecutorCustomizer dropAndCountWhenFull(ObjectProvider<MeterRegistry> meterRegistry) {
        RejectedTasks rejected = new RejectedTasks(EXECUTOR, meterRegistry.getIfAvailable());
        return executor -> executor.setRejectedExecutionHandler(new DropAndCount(rejected));
    }

    /** Drops the task, counts it and warns; never throws and never runs it on the submitting thread. */
    record DropAndCount(RejectedTasks rejected) implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            int queued = executor.getQueue().size();
            rejected.record(queued, queued + executor.getQueue().remainingCapacity(),
                    executor.isShutdown() ? "the service is stopping and it is not sent" : "it is not sent");
        }
    }
}
