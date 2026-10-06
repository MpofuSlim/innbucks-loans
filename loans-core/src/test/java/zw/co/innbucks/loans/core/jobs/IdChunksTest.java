package zw.co.innbucks.loans.core.jobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The keyset walk every chunked job rides: all of the queue, in id order, each row once, whatever the handler does
 * with a row (fails it, skips it, leaves it in the queue), and stopping when the job says stop.
 */
class IdChunksTest {

    /** A queue table: the keyset query the jobs declare, over an in-memory set of ids. */
    private static final class Queue implements BiFunction<Long, Pageable, List<Long>> {
        final TreeSet<Long> due = new TreeSet<>();
        final List<Long> afters = new ArrayList<>();

        Queue(long... ids) {
            for (long id : ids) {
                due.add(id);
            }
        }

        @Override
        public List<Long> apply(Long after, Pageable page) {
            afters.add(after);
            return due.tailSet(after, false).stream().limit(page.getPageSize()).toList();
        }
    }

    @Test
    @DisplayName("a queue longer than a chunk is walked in full, in id order, each id once")
    void walksTheWholeQueueInOrder() {
        Queue queue = new Queue(LongStream.rangeClosed(1, 250).map(i -> i * 3).toArray());
        List<Long> seen = new ArrayList<>();

        boolean reachedEnd = IdChunks.forEach(100, queue, Long::longValue, chunk -> {
            assertThat(chunk).hasSizeLessThanOrEqualTo(100);
            seen.addAll(chunk);
            // The job settles each row, so it leaves the queue (claimed, lodged, booked...).
            queue.due.removeAll(chunk);
            return true;
        });

        assertThat(reachedEnd).isTrue();
        assertThat(seen).hasSize(250).isSorted().doesNotHaveDuplicates();
        assertThat(queue.afters).containsExactly(0L, 300L, 600L);
    }

    @Test
    @DisplayName("rows that stay in the queue (failed, skipped, held) are not handed out again in the same walk")
    void aRowLeftInTheQueueIsNotRevisited() {
        Queue queue = new Queue(LongStream.rangeClosed(1, 230).toArray());
        List<Long> seen = new ArrayList<>();
        Set<Long> failing = Set.of(1L, 100L, 101L, 230L);

        IdChunks.forEach(100, queue, Long::longValue, chunk -> {
            for (Long id : chunk) {
                seen.add(id);
                if (!failing.contains(id)) {
                    queue.due.remove(id);
                }
            }
            return true;
        });

        assertThat(seen).hasSize(230).doesNotHaveDuplicates().isSorted();
        // Left for the next run, exactly as a job reading its whole queue at once left them.
        assertThat(queue.due).containsExactlyInAnyOrderElementsOf(failing);
    }

    @Test
    @DisplayName("nothing is left out when nothing leaves the queue at all")
    void aQueueThatNeverShrinksStillEnds() {
        Queue queue = new Queue(LongStream.rangeClosed(1, 300).toArray());
        List<Long> seen = new ArrayList<>();

        IdChunks.forEach(100, queue, Long::longValue, seen::addAll);

        assertThat(seen).containsExactlyElementsOf(LongStream.rangeClosed(1, 300).boxed().toList());
        // Three full chunks, then the empty one that ends the walk.
        assertThat(queue.afters).containsExactly(0L, 100L, 200L, 300L);
    }

    @Test
    @DisplayName("the job's own stop ends the walk: no later chunk is read")
    void theHandlerStopsTheWalk() {
        Queue queue = new Queue(LongStream.rangeClosed(1, 250).toArray());

        boolean reachedEnd = IdChunks.forEach(100, queue, Long::longValue, chunk -> false);

        assertThat(reachedEnd).isFalse();
        assertThat(queue.afters).containsExactly(0L);
    }

    @Test
    @DisplayName("an empty queue costs one read")
    void anEmptyQueue() {
        Queue queue = new Queue();
        List<List<Long>> chunks = new ArrayList<>();

        assertThat(IdChunks.forEach(queue, chunks::add)).isTrue();

        assertThat(chunks).isEmpty();
        assertThat(queue.afters).containsExactly(0L);
    }

    @Test
    @DisplayName("rows of any type, by their id")
    void rowsOfAnyType() {
        record Row(long id) {
        }
        List<Row> rows = LongStream.rangeClosed(1, 150).mapToObj(Row::new).toList();
        List<Row> seen = new ArrayList<>();

        IdChunks.forEach(100, (after, page) -> rows.stream().filter(row -> row.id() > after)
                .limit(page.getPageSize()).toList(), Row::id, seen::addAll);

        assertThat(seen).containsExactlyElementsOf(rows);
    }

    @Test
    @DisplayName("a query that is not a keyset (ids repeat, or out of order) is refused, never looped over")
    void aNonKeysetChunkIsRefused() {
        assertThatThrownBy(() -> IdChunks.forEach(2, (after, page) -> List.of(1L, 2L), Long::longValue,
                chunk -> true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("above 2");
        assertThatThrownBy(() -> IdChunks.forEach(10, (after, page) -> List.of(5L, 3L), Long::longValue,
                chunk -> true))
                .isInstanceOf(IllegalStateException.class);
    }
}
