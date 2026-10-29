package zw.co.innbucks.loans.core.staff.loan;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import zw.co.innbucks.loans.core.config.DispatcherExecutor;
import zw.co.innbucks.loans.core.config.RejectedTasks;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutService.Outcome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The one thread cash payouts go out on: a loan accepted is paid only once its acceptance commits; Finance's "Pay all
 * ready" walks the READY loans oldest first, a chunk at a time, each once, and stops at the first payout that may be
 * the bank's trouble; a full queue leaves a loan READY and is counted.
 */
class StaffLoanPayoutDispatcherTest {

    private final StaffLoanPayoutService payouts = mock(StaffLoanPayoutService.class);
    private final StaffLoanRepository loans = mock(StaffLoanRepository.class);
    /** Runs every task at once, on the calling thread. */
    private final StaffLoanPayoutDispatcher dispatcher = new StaffLoanPayoutDispatcher(payouts, loans, Runnable::run);
    private final List<Long> paid = new ArrayList<>();
    private final Map<Long, Outcome> outcomes = new HashMap<>();

    StaffLoanPayoutDispatcherTest() {
        when(payouts.payOut(anyLong())).thenAnswer(i -> {
            Long id = i.getArgument(0);
            paid.add(id);
            return outcomes.getOrDefault(id, Outcome.BOOKED);
        });
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** READY loans 1 to {@code count}, served a chunk at a time above the cursor, as the keyset query does. */
    private void ready(long count) {
        when(loans.findPayoutReadyIds(anyLong(), org.mockito.ArgumentMatchers.any(Pageable.class))).thenAnswer(i -> {
            long after = i.getArgument(0);
            Pageable page = i.getArgument(1);
            return LongStream.rangeClosed(1, count).filter(id -> id > after).limit(page.getPageSize()).boxed()
                    .toList();
        });
    }

    @Test
    @DisplayName("a loan accepted in a transaction is paid only once that transaction commits")
    void paidAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.afterCommit(143L);

        verifyNoInteractions(payouts);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(payouts).payOut(143L);
    }

    @Test
    @DisplayName("an acceptance that rolls back pays nothing")
    void notPaidOnRollback() {
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.afterCommit(143L);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verifyNoInteractions(payouts);
    }

    @Test
    @DisplayName("with no transaction active, it is queued at once")
    void paidNowWithoutATransaction() {
        dispatcher.afterCommit(143L);

        verify(payouts).payOut(143L);
    }

    @Test
    @DisplayName("Pay all ready walks every READY loan oldest first, across chunks, each once")
    void sweepWalksEveryChunk() {
        ready(250);

        assertThat(dispatcher.requestSweep()).isTrue();

        assertThat(paid).containsExactlyElementsOf(LongStream.rangeClosed(1, 250).boxed().toList());
    }

    @Test
    @DisplayName("a pass carries on after BOOKED, HELD and SKIPPED")
    void carriesOnPastBookedHeldAndSkipped() {
        ready(5);
        outcomes.put(2L, Outcome.HELD);
        outcomes.put(3L, Outcome.SKIPPED);

        dispatcher.requestSweep();

        assertThat(paid).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("the oldest READY payout passed over for a reason of its own on every pass never holds the rest back")
    void aPayoutPassedOverNeverHoldsTheRest() {
        ready(150);
        outcomes.put(1L, Outcome.PASSED_OVER);

        assertThat(dispatcher.payReady(Set.of())).isEqualTo(150);
        assertThat(dispatcher.payReady(Set.of())).as("and on the next pass, the same").isEqualTo(150);

        assertThat(paid).hasSize(300).contains(150L);
    }

    @Test
    @DisplayName("a pass stops at the first payout not sent, refused, in doubt or not recorded; the rest stay READY")
    void stopsAtTrouble() {
        for (Outcome stop : List.of(Outcome.NOT_SENT, Outcome.REFUSED, Outcome.IN_DOUBT, Outcome.NOT_RECORDED)) {
            paid.clear();
            outcomes.clear();
            ready(150);
            outcomes.put(3L, stop);

            assertThat(dispatcher.payReady(Set.of())).as(stop.toString()).isEqualTo(3);

            assertThat(paid).as(stop.toString()).containsExactly(1L, 2L, 3L);
        }
    }

    @Test
    @DisplayName("a payout that throws stops the pass, leaving the loan as it was")
    void stopsOnAnError() {
        ready(3);
        when(payouts.payOut(2L)).thenThrow(new IllegalStateException("boom"));

        dispatcher.requestSweep();

        verify(payouts).payOut(1L);
        verify(payouts).payOut(2L);
        verify(payouts, never()).payOut(3L);
    }

    @Test
    @DisplayName("the loans the caller is a party to are left out of their pass")
    void skipsTheCallersOwn() {
        ready(4);

        dispatcher.requestSweep(Set.of(2L, 4L));

        assertThat(paid).containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("an executor that refuses: nothing paid, false returned, never thrown")
    void refused() {
        StaffLoanPayoutDispatcher refusing = new StaffLoanPayoutDispatcher(payouts, loans, task -> {
            throw new RejectedExecutionException("full");
        });

        assertThat(refusing.request(143L)).isFalse();
        assertThat(refusing.requestSweep()).isFalse();
        refusing.afterCommit(143L);

        verifyNoInteractions(payouts);
    }

    @Test
    @DisplayName("on its own one-thread executor, a full queue is counted and the loan is left READY")
    void fullQueueCounted() throws InterruptedException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DispatcherExecutor executor = new DispatcherExecutor("staff-loan-payout", 1, null, registry);
        StaffLoanPayoutDispatcher bounded = new StaffLoanPayoutDispatcher(payouts, loans, executor);
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(payouts.payOut(1L)).thenAnswer(i -> {
            busy.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Outcome.BOOKED;
        });
        try {
            assertThat(bounded.request(1L)).isTrue();
            assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(bounded.request(2L)).as("queued").isTrue();

            assertThat(bounded.request(3L)).as("no room").isFalse();

            assertThat(registry.get(RejectedTasks.METRIC).tag("executor", "staff-loan-payout").counter().count())
                    .isEqualTo(1);
            release.countDown();
            verify(payouts, timeout(5_000)).payOut(2L);
            verify(payouts, never()).payOut(3L);
        } finally {
            release.countDown();
            bounded.stop();
        }
    }
}
