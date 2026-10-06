package zw.co.innbucks.loans.config;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

/**
 * Distributed-tracing wiring that Boot's auto-configuration does not do on its own (fleet convention, CLAUDE.md
 * "Tracing"). Boot already continues the gateway's {@code traceparent} on every incoming request, puts
 * {@code traceId} / {@code spanId} in the MDC and builds the tracer; this adds the two things specific to loans,
 * each as a customizer of what exists — no client and no executor is replaced.
 *
 * <ol>
 *   <li><b>Partners never get the trace.</b> The sender handler injects through {@link FleetOnlyPropagator}, so
 *       even an observed client never writes trace headers to InnBucks, Ndasenda, the notification API or
 *       WhatsApp.</li>
 *   <li><b>{@code @Async} hand-offs keep the trace</b> — {@link #traceContextTaskDecorator()}.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

    /**
     * Replaces Boot's sender handler (it backs off: {@code @ConditionalOnMissingBean}) with the same handler over a
     * {@link FleetOnlyPropagator}. Keeps Boot's ORDER: tracing handlers are grouped first-match-wins, so at a later
     * position the default handler would claim client observations and nothing would be propagated at all.
     */
    @Bean
    @Order(MicrometerTracingAutoConfiguration.SENDER_TRACING_OBSERVATION_HANDLER_ORDER)
    PropagatingSenderTracingObservationHandler<?> propagatingSenderTracingObservationHandler(
            Tracer tracer, Propagator propagator) {
        return new PropagatingSenderTracingObservationHandler<>(tracer, new FleetOnlyPropagator(propagator));
    }

    /**
     * The trace for every {@code @Async} task. Loans declares no executor of its own: {@code @Async} runs on Boot's
     * {@code applicationTaskExecutor}, and Boot applies a {@link TaskDecorator} bean to it (and to the scheduler,
     * where there is no trace to carry and this is a no-op). The voucher and staff-notification dispatchers apply the
     * same bean to their own executors ({@code DispatcherExecutor}), as the context's one {@link TaskDecorator}. The
     * queue's bound and what a full one does are {@code application.yml}'s and {@code AsyncExecutorConfig}'s.
     *
     * <p>Scoped to the OBSERVATION alone, not every registered context. A plain
     * {@link ContextPropagatingTaskDecorator} snapshots every {@code ThreadLocalAccessor} on the classpath — Spring
     * Security registers one for {@code SecurityContextHolder} — so it would hand the caller's authentication to
     * the notification threads, which run without one today. Tracing must not change what those threads are
     * authorised as.
     */
    @Bean
    TaskDecorator traceContextTaskDecorator() {
        ContextRegistry observationOnly = new ContextRegistry()
                .registerThreadLocalAccessor(ObservationThreadLocalAccessor.getInstance());
        return new ContextPropagatingTaskDecorator(
                ContextSnapshotFactory.builder().contextRegistry(observationOnly).build());
    }
}
