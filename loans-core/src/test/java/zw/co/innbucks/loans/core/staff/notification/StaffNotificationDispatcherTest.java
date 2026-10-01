package zw.co.innbucks.loans.core.staff.notification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Working through the PENDING notifications: oldest first, once each, past one that cannot be sent rather than round it
 * in a loop, and only once the transaction that queued them commits.
 */
class StaffNotificationDispatcherTest {

    private final TreeSet<Long> pending = new TreeSet<>();
    private final List<Long> sent = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    private Long broken;
    private StaffNotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        StaffNotificationRepository repository = mock(StaffNotificationRepository.class);
        when(repository.findIdsByOutboundStatusAfter(eq(StaffNotificationOutboundStatus.PENDING), anyLong(), any()))
                .thenAnswer(i -> pending.tailSet((Long) i.getArgument(1), false).stream()
                        .limit(((Pageable) i.getArgument(2)).getPageSize()).toList());
        when(repository.countByOutboundStatus(StaffNotificationOutboundStatus.PENDING))
                .thenAnswer(i -> (long) pending.size());
        StaffNotificationSender sender = mock(StaffNotificationSender.class);
        when(sender.send(anyLong())).thenAnswer(i -> {
            Long id = i.getArgument(0);
            if (id.equals(broken)) {
                throw new IllegalStateException("database unavailable");
            }
            pending.remove(id);
            sent.add(id);
            return true;
        });
        StaffNotificationProperties properties = new StaffNotificationProperties();
        properties.setMessagesPerSecond(0);
        Executor deferred = queued::add;
        dispatcher = new StaffNotificationDispatcher(repository, sender, properties, deferred);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void runQueued() {
        while (!queued.isEmpty()) {
            queued.removeFirst().run();
        }
    }

    @Test
    @DisplayName("a pass sends every PENDING notification once, oldest first, across batches of 100")
    void sendsAll() {
        for (long id = 1; id <= 250; id++) {
            pending.add(id);
        }

        dispatcher.requestPass();
        runQueued();

        assertThat(sent).hasSize(250).isSorted().doesNotHaveDuplicates();
        assertThat(pending).isEmpty();
    }

    @Test
    @DisplayName("one that cannot be sent is passed over, not retried in a loop; it waits for the next pass")
    void passesOverAFailure() {
        pending.addAll(List.of(1L, 2L, 3L));
        broken = 2L;

        dispatcher.requestPass();
        runQueued();

        assertThat(sent).containsExactly(1L, 3L);
        assertThat(pending).containsExactly(2L);

        broken = null;
        dispatcher.requestPass();
        runQueued();
        assertThat(sent).containsExactly(1L, 3L, 2L);
    }

    @Test
    @DisplayName("requests during a pass do not start a second one at the same time; the pass goes round again")
    void onePassAtATime() {
        pending.add(1L);
        dispatcher.requestPass();
        dispatcher.requestPass();
        dispatcher.requestPass();

        assertThat(queued).as("one pass queued, however many requests").hasSize(1);
        runQueued();
        assertThat(sent).containsExactly(1L);

        pending.add(2L);
        dispatcher.requestPass();
        runQueued();
        assertThat(sent).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("inside a transaction nothing is sent until it commits, so a rolled-back run tells nobody")
    void waitsForTheCommit() {
        pending.add(1L);
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.afterCommit();
        assertThat(queued).as("not before the commit").isEmpty();

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        runQueued();
        assertThat(sent).containsExactly(1L);
    }

    @Test
    @DisplayName("outside a transaction it starts at once; pending() counts what is waiting")
    void noTransaction() {
        pending.addAll(List.of(4L, 5L));
        assertThat(dispatcher.pending()).isEqualTo(2);

        dispatcher.afterCommit();
        runQueued();

        assertThat(sent).containsExactly(4L, 5L);
        assertThat(dispatcher.pending()).isZero();
    }

    @Test
    @DisplayName("an executor that refuses the pass leaves everything PENDING and does not wedge the next request")
    void refusedExecutor() {
        StaffNotificationRepository repository = mock(StaffNotificationRepository.class);
        StaffNotificationSender sender = mock(StaffNotificationSender.class);
        List<Runnable> accepted = new ArrayList<>();
        boolean[] refuse = {true};
        StaffNotificationDispatcher refusing = new StaffNotificationDispatcher(repository, sender,
                new StaffNotificationProperties(), runnable -> {
            if (refuse[0]) {
                throw new java.util.concurrent.RejectedExecutionException("shutting down");
            }
            accepted.add(runnable);
        });

        refusing.requestPass();
        refuse[0] = false;
        refusing.requestPass();

        assertThat(accepted).as("the second request still starts a pass").hasSize(1);
    }
}
