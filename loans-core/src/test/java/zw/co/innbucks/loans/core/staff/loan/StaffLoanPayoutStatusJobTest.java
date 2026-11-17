package zw.co.innbucks.loans.core.staff.loan;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.Pageable;
import zw.co.innbucks.loans.core.config.MarketTimeZone;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanPayoutStatusService.Checked;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cash payout's status check: stale claims held first, then every BOOKED and IN_DOUBT payout asked about, a chunk
 * at a time, then the payouts confirmed never booked that are still watched; a run that meets an unanswered or broken
 * inquiry stops there. It runs only where payouts are paid in cash.
 */
class StaffLoanPayoutStatusJobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 8, 0);

    private final StaffLoanPayoutStatusService statusService = mock(StaffLoanPayoutStatusService.class);
    private final StaffLoanRepository loans = mock(StaffLoanRepository.class);
    private final StaffLoanProperties properties = new StaffLoanProperties();
    private final StaffLoanPayoutMetrics metrics = new StaffLoanPayoutMetrics(new SimpleMeterRegistry());
    private final StaffLoanPayoutStatusJob job = new StaffLoanPayoutStatusJob(statusService, loans, properties,
            metrics, new MarketTimeZone("ZW", Clock.fixed(Instant.parse("2026-10-29T08:00:00Z"), ZoneOffset.UTC)));
    private final List<Long> checked = new ArrayList<>();
    private final Map<Long, Checked> answers = new HashMap<>();

    StaffLoanPayoutStatusJobTest() {
        when(statusService.check(anyLong())).thenAnswer(i -> {
            Long id = i.getArgument(0);
            checked.add(id);
            return answers.getOrDefault(id, Checked.BOOKED);
        });
    }

    /** {@code from} to {@code to} awaiting the inquiry, and {@code failed} watched, served by keyset as the queries do. */
    private void queues(long from, long to, List<Long> failed) {
        when(loans.findPayoutAwaitingInquiryIds(anyLong(), any(Pageable.class))).thenAnswer(i -> {
            long after = i.getArgument(0);
            Pageable page = i.getArgument(1);
            return LongStream.rangeClosed(from, to).filter(id -> id > after).limit(page.getPageSize()).boxed().toList();
        });
        when(loans.findPayoutFailedSinceIds(eq(NOW.minusDays(14)), anyLong(), any(Pageable.class))).thenAnswer(i -> {
            long after = i.getArgument(1);
            return failed.stream().filter(id -> id > after).toList();
        });
    }

    @Test
    @DisplayName("holds stale claims first, then asks about every BOOKED and IN_DOUBT payout across chunks, then the"
            + " watched ones")
    void order() {
        queues(1, 230, List.of(500L, 501L));

        assertThat(job.runOnce(NOW)).isTrue();

        List<Long> expected = new ArrayList<>(LongStream.rangeClosed(1, 230).boxed().toList());
        expected.addAll(List.of(500L, 501L));
        assertThat(checked).containsExactlyElementsOf(expected);
        InOrder order = inOrder(statusService);
        order.verify(statusService).holdAbandonedClaims(NOW);
        order.verify(statusService).check(1L);
    }

    @Test
    @DisplayName("a run carries on past paid, booked, declined, not found, mismatched and paid-after-cancel answers")
    void carriesOn() {
        queues(1, 7, List.of());
        answers.put(1L, Checked.PAID);
        answers.put(2L, Checked.NOT_FOUND);
        answers.put(3L, Checked.ECHO_MISMATCH);
        answers.put(4L, Checked.UNKNOWN_STATUS);
        answers.put(5L, Checked.PAID_AFTER_CANCEL);
        answers.put(6L, Checked.SKIPPED);
        answers.put(7L, Checked.DECLINED);

        assertThat(job.runOnce(NOW)).isTrue();

        assertThat(checked).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
    }

    @Test
    @DisplayName("one loan whose answer says nothing, or cannot be saved, on every run never holds the queue: the run"
            + " goes on to every loan behind it, and the watched ones")
    void oneLoanNeverHoldsTheHead() {
        queues(1, 150, List.of(500L));
        answers.put(1L, Checked.UNREADABLE);
        answers.put(2L, Checked.NOT_RECORDED);

        assertThat(job.runOnce(NOW)).isTrue();
        assertThat(job.runOnce(NOW)).as("and on the next run, the same").isTrue();

        assertThat(checked).hasSize(2 * 151).contains(150L, 500L);
    }

    @Test
    @DisplayName("an unanswered inquiry stops the run: nothing after it, not even the watched payouts")
    void stopsWhenUnanswered() {
        queues(1, 150, List.of(500L));
        answers.put(3L, Checked.UNANSWERED);

        assertThat(job.runOnce(NOW)).isFalse();

        assertThat(checked).containsExactly(1L, 2L, 3L);
        verify(statusService, never()).check(500L);
    }

    @Test
    @DisplayName("an inquiry that broke stops the run")
    void stopsOnError() {
        queues(1, 5, List.of());
        answers.put(2L, Checked.ERROR);

        assertThat(job.runOnce(NOW)).isFalse();

        assertThat(checked).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("a check that throws stops the run, counted as an inquiry error; the scheduler thread never sees it")
    void stopsOnAThrow() {
        queues(1, 5, List.of());
        when(statusService.check(2L)).thenThrow(new IllegalStateException("boom"));

        job.run();

        verify(statusService, never()).check(3L);
        assertThat(metrics.counted(StaffLoanPayoutMetrics.Event.INQUIRY_ERROR)).isEqualTo(1);
    }

    @Test
    @DisplayName("a failure holding stale claims does not stop the inquiries")
    void holdFailureCarriesOn() {
        queues(1, 2, List.of());
        when(statusService.holdAbandonedClaims(any())).thenThrow(new IllegalStateException("db"));

        assertThat(job.runOnce(NOW)).isTrue();

        assertThat(checked).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("each run refreshes the backlog gauges: how many in each open payout status, and the oldest's wait")
    void gauges() {
        queues(1, 0, List.of());
        when(loans.payoutBacklog()).thenReturn(List.of(
                new StaffLoanPayoutBacklog(StaffLoanPayoutStatus.READY, 3L, NOW.minusHours(2), null),
                new StaffLoanPayoutBacklog(StaffLoanPayoutStatus.SENDING, 1L, NOW.minusDays(1), NOW.minusMinutes(5)),
                new StaffLoanPayoutBacklog(StaffLoanPayoutStatus.IN_DOUBT, 2L, NOW.minusDays(2), null)));

        job.runOnce(NOW);

        assertThat(metrics.pending(StaffLoanPayoutStatus.READY)).isEqualTo(3);
        assertThat(metrics.oldestSeconds(StaffLoanPayoutStatus.READY)).isEqualTo(7200);
        assertThat(metrics.pending(StaffLoanPayoutStatus.SENDING)).isEqualTo(1);
        assertThat(metrics.oldestSeconds(StaffLoanPayoutStatus.SENDING)).as("since the claim").isEqualTo(300);
        assertThat(metrics.pending(StaffLoanPayoutStatus.IN_DOUBT)).isEqualTo(2);
        assertThat(metrics.pending(StaffLoanPayoutStatus.BOOKED)).isZero();
        assertThat(metrics.oldestSeconds(StaffLoanPayoutStatus.REFUSED)).isZero();

        when(loans.payoutBacklog()).thenReturn(List.of());
        job.runOnce(NOW);
        assertThat(metrics.pending(StaffLoanPayoutStatus.READY)).as("none left").isZero();
    }

    @Test
    @DisplayName("the job exists only where payouts are paid in cash: never by default, never for VOUCHER")
    void onlyForCash() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(StaffLoanPayoutStatusService.class, () -> statusService)
                .withBean(StaffLoanRepository.class, () -> loans)
                .withBean(StaffLoanProperties.class, () -> properties)
                .withBean(StaffLoanPayoutMetrics.class, () -> metrics)
                .withBean(MarketTimeZone.class, () -> new MarketTimeZone("ZW"))
                .withUserConfiguration(StaffLoanPayoutStatusJob.class);

        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(StaffLoanPayoutStatusJob.class));
        runner.withPropertyValues("loans.staff-loans.payout.method=VOUCHER")
                .run(context -> assertThat(context).doesNotHaveBean(StaffLoanPayoutStatusJob.class));
        runner.withPropertyValues("loans.staff-loans.payout.method=CASH")
                .run(context -> assertThat(context).hasSingleBean(StaffLoanPayoutStatusJob.class));
        runner.withPropertyValues("loans.staff-loans.payout.method=cash")
                .run(context -> assertThat(context).hasSingleBean(StaffLoanPayoutStatusJob.class));
    }
}
