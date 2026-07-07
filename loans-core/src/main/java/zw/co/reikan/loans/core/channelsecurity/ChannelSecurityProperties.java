package zw.co.reikan.loans.core.channelsecurity;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for the cross-channel security layer.
 *
 * <p><b>Rollout safety:</b> {@code mode} defaults to {@link Mode#MONITOR} so
 * the layer observes, audits and replays idempotent responses WITHOUT
 * rejecting existing traffic. Flip to {@code ENFORCE} per environment once
 * every channel gateway sends the headers. This is how the primitives are
 * instilled without changing how the (already functional) loans flow works.</p>
 */
@Data
@ConfigurationProperties(prefix = "channel-security")
public class ChannelSecurityProperties {

    public enum Mode {
        /** Layer bypassed entirely. */
        DISABLED,
        /** Verify + audit + replay idempotent responses, but never reject. */
        MONITOR,
        /** Reject missing/invalid signatures, replays, and velocity breaches. */
        ENFORCE
    }

    private Mode mode = Mode.MONITOR;

    /** channelId -> shared HMAC secret. Bind from env/vault, never commit values. */
    private Map<String, String> secrets = new HashMap<>();

    /** Accepted clock skew for the signed timestamp (replay window bound). */
    private Duration clockSkew = Duration.ofMinutes(5);

    /** Idempotency record TTL (Stripe keeps 24h; we allow 24–48h). */
    private Duration idempotencyTtl = Duration.ofHours(24);

    /** Mutating paths guarded by the filter. */
    private List<String> protectedPaths = List.of("/api/**");

    /** Paths always exempt (public auth + docs). */
    private List<String> exemptPaths = List.of("/api/auth/**", "/v3/api-docs/**", "/swagger-ui/**", "/spec.html");

    /** Velocity: max mutating submissions per actor within the window. */
    private int velocityLimit = 50;

    private Duration velocityWindow = Duration.ofSeconds(60);
}
