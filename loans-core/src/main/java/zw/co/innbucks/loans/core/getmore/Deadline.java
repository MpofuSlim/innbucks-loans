package zw.co.innbucks.loans.core.getmore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A budget for a sequence of GetMore calls (the inline issuance on an accept request). It is checked BEFORE each call,
 * never during one: a call that has started runs to its own timeouts, so its outcome is always one of the store's.
 *
 * @param at the instant after which no further call starts; null for no deadline
 */
public record Deadline(Instant at) {

    private static final Deadline NONE = new Deadline(null);

    public static Deadline none() {
        return NONE;
    }

    public static Deadline after(Clock clock, Duration budget) {
        return new Deadline(clock.instant().plus(budget));
    }

    /** @throws GetMoreUnavailableException {@code DEADLINE} when the budget has run out */
    public void check(Clock clock) {
        if (at != null && !clock.instant().isBefore(at)) {
            throw new GetMoreUnavailableException(null, GetMoreUnavailableException.Reason.DEADLINE,
                    "the time allowed for this attempt ran out before the next call");
        }
    }
}
