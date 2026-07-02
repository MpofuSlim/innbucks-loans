package zw.co.reikan.loans.core.channelsecurity;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityRateLimiterTest {

    private VelocityRateLimiter limiter(int limit) {
        ChannelSecurityProperties properties = new ChannelSecurityProperties();
        properties.setVelocityLimit(limit);
        properties.setVelocityWindow(Duration.ofSeconds(60));
        return new VelocityRateLimiter(properties);
    }

    @Test
    void submissionsWithinLimit_pass() {
        VelocityRateLimiter limiter = limiter(50);
        for (int i = 0; i < 50; i++) {
            assertTrue(limiter.recordAndCheck("whatsapp:agent-007"), "submission " + i + " should pass");
        }
    }

    @Test
    void fiftyFirstSubmissionInWindow_trips() {
        VelocityRateLimiter limiter = limiter(50);
        for (int i = 0; i < 50; i++) {
            limiter.recordAndCheck("whatsapp:agent-007");
        }
        assertFalse(limiter.recordAndCheck("whatsapp:agent-007"),
                "51st submission inside the window must trip the velocity limit");
    }

    @Test
    void actorsAreIsolatedFromEachOther() {
        VelocityRateLimiter limiter = limiter(2);
        limiter.recordAndCheck("whatsapp:agent-a");
        limiter.recordAndCheck("whatsapp:agent-a");
        assertFalse(limiter.recordAndCheck("whatsapp:agent-a"));
        // A different agent on the same channel is unaffected.
        assertTrue(limiter.recordAndCheck("whatsapp:agent-b"));
    }
}
