package zw.co.innbucks.loans.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.web.ApiPaths;

import java.io.IOException;

/**
 * Keeps borrower sessions and staff sessions apart. A Staff Grocery Loan borrower signed in from the SuperApp
 * ({@link JwtService#isBorrowerToken}) reaches the borrower endpoints and nothing else: many staff endpoints ask only
 * for a valid token, and a borrower's must never submit a loan application, read a report or see another staff member's
 * record. And the borrower endpoints answer borrowers only: they act for the staff member a session names, which a staff
 * user's session does not.
 *
 * <p>Both refusals are the same 403 a role check gives. Deliberately not a bean, for the reason
 * {@link TemporaryPasswordFilter} gives.
 */
@Slf4j
final class BorrowerSessionFilter extends OncePerRequestFilter {

    static final String BORROWER_PATHS = ApiPaths.BASE + "/borrower/**";
    private static final RequestMatcher BORROWER = PathPatternRequestMatcher.withDefaults().matcher(BORROWER_PATHS);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            boolean borrower = JwtService.isBorrowerToken(token.getToken());
            if (borrower != BORROWER.matches(request)) {
                log.warn("Refused {} {} for {}: {}", request.getMethod(), request.getRequestURI(), token.getName(),
                        borrower ? "a borrower session reaches the borrower endpoints only"
                                : "the borrower endpoints answer borrower sessions only");
                SecurityErrorResponses.forbidden(request, response, null);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
