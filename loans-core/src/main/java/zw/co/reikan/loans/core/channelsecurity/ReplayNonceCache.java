package zw.co.reikan.loans.core.channelsecurity;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Thread-safe replay guard: a nonce may be seen exactly once inside the
 * signature clock-skew window. Backed by Caffeine (already this codebase's
 * caching layer) with a TTL of 2x the skew window, so an attacker replaying a
 * captured request — even with a valid signature and in-window timestamp —
 * is rejected on the second presentation.
 */
@Component
public class ReplayNonceCache {

    private final Cache<String, Boolean> seen;

    public ReplayNonceCache(ChannelSecurityProperties properties) {
        Duration ttl = properties.getClockSkew().multipliedBy(2);
        this.seen = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(500_000)
                .build();
    }

    /**
     * @return true the FIRST time this (channelId, nonce) pair is presented;
     *         false on any replay. Atomic — concurrent duplicates race safely.
     */
    public boolean markIfFirstUse(String channelId, String nonce) {
        String key = channelId + ':' + nonce;
        // asMap().putIfAbsent is atomic: exactly one caller wins.
        return seen.asMap().putIfAbsent(key, Boolean.TRUE) == null;
    }
}
