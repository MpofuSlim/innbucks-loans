package zw.co.reikan.loans.core.channelsecurity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Application-level transaction velocity tracker (fraud primitive).
 *
 * <p>Fixed-window counter per actor (channelId + authenticated principal):
 * an agent hammering {@code POST /api/loans} 50+ times in 60 seconds trips
 * the limiter. In ENFORCE mode the request is rejected with 429; in MONITOR
 * mode it is flagged to {@code audit_logs} so the fraud desk sees the
 * pattern without blocking production traffic.</p>
 */
@Component
public class VelocityRateLimiter {

    private final Cache<String, AtomicInteger> windows;
    private final int limit;

    public VelocityRateLimiter(ChannelSecurityProperties properties) {
        Duration window = properties.getVelocityWindow();
        this.limit = properties.getVelocityLimit();
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(200_000)
                .build();
    }

    /**
     * Records one mutating submission for the actor.
     *
     * @return true if the actor is within limits; false if this submission
     *         breaches the velocity threshold.
     */
    public boolean recordAndCheck(String actorKey) {
        AtomicInteger counter = windows.get(actorKey, k -> new AtomicInteger());
        return counter.incrementAndGet() <= limit;
    }

    public int currentCount(String actorKey) {
        AtomicInteger counter = windows.getIfPresent(actorKey);
        return counter == null ? 0 : counter.get();
    }
}
