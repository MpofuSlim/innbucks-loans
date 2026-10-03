package zw.co.innbucks.loans.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates the cell's Prometheus on {@code /actuator/prometheus} by the fleet's static scrape token: the
 * {@code X-Metrics-Token} header, compared in constant time with {@code monitoring.scrape-token}
 * ({@code METRICS_SCRAPE_TOKEN}). The same header, value and shape as every ticketing service's
 * {@code MetricsScrapeAuthFilter}, so one Prometheus job definition fits loans too.
 *
 * <p>Why not a loans token: a scraper cannot renew one, and a static token file would go stale at its first
 * expiry, blinding every alert. Why not {@code permitAll}: the endpoint names every route and its error and
 * request counts, and "reachable in-cluster only" has not been a safe assumption for service-local paths in
 * the fleet. Blank (the default) authenticates nothing, so the endpoint is a 401 until the cell provisions the
 * token. A wrong or absent header is never rejected here: it falls through to the 401 of the chain this filter
 * sits in ({@link ApiSecurityConfig#metricsSecurityFilterChain}).
 *
 * <p>Not a {@code @Component}: Boot would register it as a servlet filter on every request as well.
 */
public class MetricsScrapeAuthFilter extends OncePerRequestFilter {

    static final String SCRAPE_PATH = "/actuator/prometheus";
    static final String TOKEN_HEADER = "X-Metrics-Token";
    static final String SCRAPER_ROLE = "METRICS_SCRAPE";

    private final byte[] scrapeToken;

    public MetricsScrapeAuthFilter(String scrapeToken) {
        String trimmed = scrapeToken == null ? "" : scrapeToken.trim();
        this.scrapeToken = trimmed.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SCRAPE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(TOKEN_HEADER);
        if (scrapeToken.length > 0
                && presented != null
                && MessageDigest.isEqual(scrapeToken, presented.getBytes(StandardCharsets.UTF_8))
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "metrics-scraper", null, List.of(new SimpleGrantedAuthority("ROLE_" + SCRAPER_ROLE))));
        }
        chain.doFilter(request, response);
    }
}
