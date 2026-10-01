package zw.co.innbucks.loans.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.co.innbucks.loans.core.user.UserGrantPolicy;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.web.ApiPaths;

import java.io.IOException;
import java.util.Set;

/**
 * Keeps the two voucher roles to the voucher endpoints. Many endpoints only ask for a valid token (submitting a loan,
 * the reports, a draft), and a role check on each would have to be remembered on every new one; this answers the
 * question once, by path, before any controller runs.
 * <ul>
 *   <li>A token holding GETMORE, GetMore's till integration, reaches the validation and redemption endpoints and its
 *       own password change, and nothing else, whatever other role it holds: an outside party's credential is never
 *       also a staff one.</li>
 *   <li>A token holding VOUCHER_SUPPORT and no other role reaches the voucher screens and its own password change.
 *       Held with another role, that role's endpoints are open to it as usual.</li>
 * </ul>
 * Anything else is the same 403 a role check gives. Runs after {@link TemporaryPasswordFilter}, so a temporary password
 * is still changed first. Deliberately not a bean, for the reason {@link TemporaryPasswordFilter} gives.
 */
@Slf4j
final class VoucherRoleFilter extends OncePerRequestFilter {

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();
    private static final RequestMatcher CHANGE_PASSWORD = PATHS.matcher(HttpMethod.PUT, ApiPaths.BASE + "/me/password");

    private static final RequestMatcher GETMORE = new OrRequestMatcher(CHANGE_PASSWORD,
            PATHS.matcher(HttpMethod.POST, ApiPaths.BASE + "/voucher-validations"),
            PATHS.matcher(HttpMethod.POST, ApiPaths.BASE + "/voucher-redemptions"));

    private static final RequestMatcher VOUCHER_SUPPORT = new OrRequestMatcher(CHANGE_PASSWORD,
            PATHS.matcher(HttpMethod.GET, ApiPaths.BASE + "/vouchers"),
            PATHS.matcher(HttpMethod.GET, ApiPaths.BASE + "/vouchers/{voucherId}"),
            PATHS.matcher(HttpMethod.POST, ApiPaths.BASE + "/vouchers/{voucherId}/code-reveals"),
            PATHS.matcher(HttpMethod.POST, ApiPaths.BASE + "/vouchers/{voucherId}/deliveries"));

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (authentication != null) {
            Set<UserGroup> groups = UserGrantPolicy.callerGroups(authentication.getAuthorities());
            boolean confined = groups.contains(UserGroup.GETMORE) ? !GETMORE.matches(request)
                    : groups.equals(Set.of(UserGroup.VOUCHER_SUPPORT)) && !VOUCHER_SUPPORT.matches(request);
            if (confined) {
                log.warn("Refused {} {} for {}: a voucher role reaches the voucher endpoints only", request.getMethod(),
                        request.getRequestURI(), authentication.getName());
                SecurityErrorResponses.forbidden(request, response, null);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
