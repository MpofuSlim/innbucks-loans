package zw.co.innbucks.loans.core.jobs;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Walks a job's queue in bounded chunks, by keyset on the id: each chunk is the next {@code size} rows above the last
 * id handed out, in id order. A job used to read its whole queue at once (and some read every row in full), so its
 * memory, and the transaction that read it, grew with the backlog.
 *
 * <p>What the keyset guarantees, whatever the handler does with a chunk:
 * <ul>
 *   <li>every row the query admits when its chunk is read is handed out, in ascending id order;</li>
 *   <li>no row is handed out twice in one walk, even if it is still in the queue afterwards (it failed, was skipped,
 *       or was deferred): the next chunk starts strictly above the last id of this one. A row that stays in the queue
 *       is the NEXT run's, exactly as when the job read its whole queue at once;</li>
 *   <li>so a row that fails never stalls the walk, and is never retried within it.</li>
 * </ul>
 * A row that enters the queue during the walk with an id above the cursor is reached in this walk; one below it waits
 * for the next run. The query must filter {@code id > :after} and order by id; a chunk that does not move the cursor
 * strictly forward is refused rather than walked again.
 */
public final class IdChunks {

    /** Rows per chunk: {@code hibernate.default_batch_fetch_size}, so a chunk's associations load in one IN query. */
    public static final int SIZE = 100;

    private IdChunks() {
    }

    /**
     * Hands each chunk to {@code handler} until the queue is exhausted or the handler returns false (the job's own
     * "stop this run").
     *
     * @param chunk   the next chunk: rows with ids above {@code after}, ascending, at most {@code page.getPageSize()}
     * @param idOf    a row's id
     * @param handler true to carry on to the next chunk, false to end the walk
     * @return whether the walk reached the end of the queue (false: the handler stopped it)
     * @throws IllegalStateException a chunk whose ids are not strictly ascending above the cursor
     */
    public static <T> boolean forEach(int size, BiFunction<Long, Pageable, List<T>> chunk, ToLongFunction<T> idOf,
                                      Predicate<List<T>> handler) {
        if (size < 1) {
            throw new IllegalArgumentException("A chunk holds at least one row");
        }
        Pageable page = PageRequest.of(0, size);
        long after = 0;
        while (true) {
            List<T> rows = chunk.apply(after, page);
            if (rows == null || rows.isEmpty()) {
                return true;
            }
            long previous = after;
            for (T row : rows) {
                long id = idOf.applyAsLong(row);
                if (id <= previous) {
                    // Not a keyset query (or not in id order): walking on could hand the same rows out again.
                    throw new IllegalStateException("A chunk must hold ids above " + after
                            + " in ascending order; got " + id + " after " + previous);
                }
                previous = id;
            }
            if (!handler.test(rows)) {
                return false;
            }
            if (rows.size() < size) {
                return true;
            }
            after = previous;
        }
    }

    /** {@link #forEach(int, BiFunction, ToLongFunction, Predicate)} over ids, in chunks of {@link #SIZE}. */
    public static boolean forEach(BiFunction<Long, Pageable, List<Long>> chunk, Predicate<List<Long>> handler) {
        return forEach(SIZE, chunk, Long::longValue, handler);
    }

    /** {@link #forEach(int, BiFunction, ToLongFunction, Predicate)} in chunks of {@link #SIZE}. */
    public static <T> boolean forEach(BiFunction<Long, Pageable, List<T>> chunk, ToLongFunction<T> idOf,
                                      Predicate<List<T>> handler) {
        return forEach(SIZE, chunk, idOf, handler);
    }
}
