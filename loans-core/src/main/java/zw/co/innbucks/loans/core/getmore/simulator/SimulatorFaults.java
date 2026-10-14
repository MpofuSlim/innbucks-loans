package zw.co.innbucks.loans.core.getmore.simulator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import zw.co.innbucks.loans.core.getmore.GetMoreException.Operation;
import zw.co.innbucks.loans.core.getmore.GetMoreOutcomeUnknownException;
import zw.co.innbucks.loans.core.getmore.GetMoreRejectedException;
import zw.co.innbucks.loans.core.getmore.GetMoreUnavailableException;

import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Faults a staging tester queues for the simulated store's next calls, so the failure paths above the port (a lost
 * generate, an unreachable store, a refusal) can be driven on purpose. In memory and per process: the loans Deployment
 * is one replica, recreated on every rollout, so a queued fault lives exactly as long as the pod.
 *
 * <ul>
 *   <li>{@link FaultKind#UNAVAILABLE}: the call is refused as not reached, before the store acts.</li>
 *   <li>{@link FaultKind#REJECTED}: the store answers 400 "Simulated rejection", before it acts.</li>
 *   <li>{@link FaultKind#LOSE_RESPONSE}: on a write the store acts and commits, then the answer is lost (outcome
 *       unknown); on a read it is the same as UNAVAILABLE.</li>
 * </ul>
 * Each queued fault fires on one call; {@link #inject} queues 1 to {@value #MAX_TIMES}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "loans.getmore", name = "mode", havingValue = "SIMULATED")
public class SimulatorFaults {

    public static final int MAX_TIMES = 10;

    public enum FaultKind { UNAVAILABLE, REJECTED, LOSE_RESPONSE }

    private final Map<Operation, Deque<FaultKind>> pending = new ConcurrentHashMap<>();

    /** The next {@code times} calls of {@code operation} fail as {@code kind}, after any already queued. */
    public void inject(Operation operation, FaultKind kind, int times) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(kind, "kind");
        if (times < 1 || times > MAX_TIMES) {
            throw new IllegalArgumentException("A fault fires on the next 1 to " + MAX_TIMES + " calls, not " + times);
        }
        Deque<FaultKind> queue = pending.computeIfAbsent(operation, ignored -> new ConcurrentLinkedDeque<>());
        for (int i = 0; i < times; i++) {
            queue.addLast(kind);
        }
        log.warn("GetMore simulator: the next {} {} call(s) will {}", times, operation, kind);
    }

    /** Drops every queued fault. */
    public void clear() {
        pending.clear();
        log.warn("GetMore simulator: faults cleared");
    }

    /** What is queued, per operation, in firing order. */
    public Map<Operation, List<FaultKind>> pending() {
        Map<Operation, List<FaultKind>> out = new EnumMap<>(Operation.class);
        pending.forEach((operation, queue) -> {
            if (!queue.isEmpty()) {
                out.put(operation, List.copyOf(new ArrayList<>(queue)));
            }
        });
        return out;
    }

    /**
     * Before the store acts: takes the next fault queued for {@code operation}, if any. UNAVAILABLE and REJECTED (and
     * LOSE_RESPONSE on a read) are thrown here; a write's LOSE_RESPONSE is returned, for {@link #after}.
     *
     * @return the fault to apply once the store has acted, or null
     */
    public FaultKind before(Operation operation) {
        Deque<FaultKind> queue = pending.get(operation);
        FaultKind fault = queue == null ? null : queue.pollFirst();
        if (fault == null) {
            return null;
        }
        log.warn("GetMore simulator: {} fault on {}", fault, operation);
        return switch (fault) {
            case UNAVAILABLE -> throw new GetMoreUnavailableException(operation,
                    GetMoreUnavailableException.Reason.NOT_REACHED, "simulated fault: the store was not reached");
            case REJECTED -> throw new GetMoreRejectedException(operation, 400,
                    GetMoreRejectedException.Reason.INVALID, "Simulated rejection");
            case LOSE_RESPONSE -> {
                if (!operation.isWrite()) {
                    throw new GetMoreUnavailableException(operation, GetMoreUnavailableException.Reason.NOT_REACHED,
                            "simulated fault: the answer was lost");
                }
                yield fault;
            }
        };
    }

    /** After the store acted and committed: a write's lost answer is an unknown outcome. */
    public void after(Operation operation, FaultKind fault) {
        if (fault == FaultKind.LOSE_RESPONSE) {
            throw new GetMoreOutcomeUnknownException(operation,
                    "simulated fault: the store acted and committed, and its answer was lost");
        }
    }
}
