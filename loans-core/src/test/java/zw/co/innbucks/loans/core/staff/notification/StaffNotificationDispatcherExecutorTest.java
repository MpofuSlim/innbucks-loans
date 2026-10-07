package zw.co.innbucks.loans.core.staff.notification;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.task.TaskDecorator;
import zw.co.innbucks.loans.core.config.RejectedTasks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dispatcher as Spring builds it: a pass runs on the {@code staff-notifications} thread under the context's
 * {@link TaskDecorator} (the trace, in the application), and its refusal counter exists from the start.
 */
class StaffNotificationDispatcherExecutorTest {

    private static final ThreadLocal<String> MARK = new ThreadLocal<>();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private StaffNotificationDispatcher dispatcher;

    @AfterEach
    void stop() {
        if (dispatcher != null) {
            dispatcher.stop();
        }
    }

    @Test
    @DisplayName("a pass runs on its own thread, decorated, and the refusal counter is registered at 0")
    void decoratedPass() {
        StaffNotificationRepository repository = mock(StaffNotificationRepository.class);
        when(repository.findIdsByOutboundStatusAfter(eq(StaffNotificationOutboundStatus.PENDING), eq(0L), any()))
                .thenReturn(List.of(5L));
        List<String> sends = new CopyOnWriteArrayList<>();
        StaffNotificationSender sender = mock(StaffNotificationSender.class);
        when(sender.send(anyLong())).thenAnswer(i -> sends.add(i.getArgument(0) + " " + MARK.get() + " on "
                + Thread.currentThread().getName()));
        StaffNotificationProperties properties = new StaffNotificationProperties();
        properties.setMessagesPerSecond(0);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", registry);
        beans.registerSingleton("traceContextTaskDecorator", (TaskDecorator) task -> () -> {
            MARK.set("decorated");
            try {
                task.run();
            } finally {
                MARK.remove();
            }
        });
        dispatcher = new StaffNotificationDispatcher(repository, sender, properties,
                beans.getBeanProvider(TaskDecorator.class), beans.getBeanProvider(MeterRegistry.class));

        assertThat(registry.get(RejectedTasks.METRIC).tag("executor", "staff-notifications").counter().count())
                .isZero();
        dispatcher.requestPass();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !sends.isEmpty());
        assertThat(sends).containsExactly("5 decorated on staff-notifications");
    }
}
