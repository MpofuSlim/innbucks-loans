package zw.co.innbucks.loans.it;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The system clock moved on by however far a test says, for an IT that has to step past a waiting period (a
 * back-off, a retry-after) without sleeping through it. Time still passes between steps, so everything stamped in a
 * run keeps its order; only {@link #advance} jumps. A view of it in another zone ({@link #withZone}, which
 * {@code MarketTimeZone} takes for the market's day) moves with it.
 */
final class SettableClock extends Clock {

    private final AtomicReference<Duration> offset;
    private final ZoneId zone;

    SettableClock() {
        this(new AtomicReference<>(Duration.ZERO), ZoneOffset.UTC);
    }

    private SettableClock(AtomicReference<Duration> offset, ZoneId zone) {
        this.offset = offset;
        this.zone = zone;
    }

    /** Moves the clock on by {@code by}, for every reader and every zoned view from now on. */
    void advance(Duration by) {
        offset.accumulateAndGet(by, Duration::plus);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return other.equals(zone) ? this : new SettableClock(offset, other);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }
}
