package zw.co.reikan.loans.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.channelsecurity.ChannelSecurityFilter;
import zw.co.reikan.loans.core.channelsecurity.ChannelSecurityProperties;
import zw.co.reikan.loans.core.channelsecurity.HmacSignatureVerifier;
import zw.co.reikan.loans.core.channelsecurity.ReplayNonceCache;
import zw.co.reikan.loans.core.channelsecurity.VelocityRateLimiter;
import zw.co.reikan.loans.core.idempotency.IdempotencyService;

/**
 * Registers the cross-channel security filter on the API surface only (the
 * embedded UI serves browser form posts and is deliberately out of scope).
 *
 * <p>Runs AFTER the Spring Security chain so a request must survive OAuth2/JWT
 * auth before spending idempotency/ledger resources — defence in depth, with
 * RBAC untouched.</p>
 */
@Configuration
@RequiredArgsConstructor
public class ChannelSecurityFilterConfig {

    private final ChannelSecurityProperties properties;
    private final HmacSignatureVerifier signatureVerifier;
    private final ReplayNonceCache nonceCache;
    private final VelocityRateLimiter velocityRateLimiter;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;

    @Bean
    public FilterRegistrationBean<ChannelSecurityFilter> channelSecurityFilter() {
        ChannelSecurityFilter filter = new ChannelSecurityFilter(properties, signatureVerifier,
                nonceCache, velocityRateLimiter, idempotencyService, auditService);
        FilterRegistrationBean<ChannelSecurityFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        registration.addUrlPatterns("/*"); // path scoping handled by shouldNotFilter
        return registration;
    }
}
