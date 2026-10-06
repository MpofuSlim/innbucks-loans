package zw.co.innbucks.loans.core.voucher;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dispatcher as Spring builds it: its executor's queue is bounded, a send that does not fit is counted and never
 * thrown at the committing caller (its voucher simply stays PENDING for the sweep), and every send runs under the
 * context's {@link TaskDecorator}, which in the application carries the trace.
 */
class VoucherDeliveryDispatcherExecutorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> sends = new CopyOnWriteArrayList<>();
    private VoucherDeliveryDispatcher dispatcher;

    @AfterEach
    void stop() {
        release.countDown();
        if (dispatcher != null) {
            dispatcher.stop();
        }
    }

    private VoucherDeliveryDispatcher dispatcher(TaskDecorator decorator, boolean blockFirst) {
        VoucherDeliverySender sender = mock(VoucherDeliverySender.class);
        CountDownLatch first = new CountDownLatch(1);
        when(sender.send(anyLong(), anyString())).thenAnswer(i -> {
            if (blockFirst && first.getCount() == 1) {
                first.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            sends.add(i.getArgument(0) + " " + MARK.get());
            return true;
        });
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", registry);
        if (decorator != null) {
            beans.registerSingleton("traceContextTaskDecorator", decorator);
        }
        return new VoucherDeliveryDispatcher(mock(VoucherRepository.class), sender,
                beans.getBeanProvider(TaskDecorator.class), beans.getBeanProvider(MeterRegistry.class));
    }

    private static final ThreadLocal<String> MARK = new ThreadLocal<>();

    private double rejected() {
        return registry.get(RejectedTasks.METRIC).tag("executor", "voucher-delivery").counter().count();
    }

    @Test
    @DisplayName("a full queue: the send is counted and dropped, the caller gets no exception, the rest still go out")
    void fullQueue() {
        dispatcher = dispatcher(null, true);
        assertThat(rejected()).as("registered at 0").isZero();

        dispatcher.afterCommit(0L, "agent");                     // on the thread, held
        for (long id = 1; id <= VoucherDeliveryDispatcher.QUEUE_CAPACITY; id++) {
            dispatcher.afterCommit(id, "agent");                  // queued: fills it
        }
        assertThatCode(() -> dispatcher.afterCommit(-1L, "agent")).doesNotThrowAnyException();
        assertThatCode(dispatcher::requestSweep).doesNotThrowAnyException();

        assertThat(rejected()).isEqualTo(2);
        release.countDown();
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> sends.size() == VoucherDeliveryDispatcher.QUEUE_CAPACITY + 1);
        assertThat(sends).noneMatch(send -> send.startsWith("-1 "));
    }

    @Test
    @DisplayName("each send runs under the context's task decorator (the trace, in the application)")
    void decorated() {
        TaskDecorator decorator = task -> () -> {
            MARK.set("decorated");
            try {
                task.run();
            } finally {
                MARK.remove();
            }
        };
        dispatcher = dispatcher(decorator, false);

        dispatcher.afterCommit(7L, "agent");

        await().atMost(5, TimeUnit.SECONDS).until(() -> !sends.isEmpty());
        assertThat(sends).containsExactly("7 decorated");
    }
}
