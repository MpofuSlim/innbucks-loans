package zw.co.reikan.loans.core.loan;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import zw.co.reikan.loans.core.user.FindUserService;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserGroup;

import java.util.List;

/**
 * Maps a caller's roles onto the {@link LoanReadScope} of the generic loan reads
 * ({@code /api/loans/search}, {@code /api/loans/{id}}). Same shape as
 * {@code MerchantController.resolveUserId}, applied to the caller's OWN merchant
 * because these endpoints take no merchant code:
 * <ul>
 *   <li>BULKIT_ADMIN, CREDIT_MANAGER, FINANCE — lender-side staff who approve,
 *       disburse and reconcile across merchants: every loan.</li>
 *   <li>AGENTS, and any role not named here — only loans they created, within
 *       their merchant. Unknown roles fall to the narrowest scope, never the widest.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class LoanReadScopeResolver {

    private static final List<String> PLATFORM_ROLES = List.of(UserGroup.BULKIT_ADMIN.name(),
            UserGroup.CREDIT_MANAGER.name(), UserGroup.FINANCE.name());

    private final FindUserService findUserService;

    public LoanReadScope resolve(Jwt token) {
        if (findUserService.hasAnyRole(token, PLATFORM_ROLES)) {
            return LoanReadScope.platform();
        }
        User user = findUserService.resolveUserFromAccessToken(token)
                .orElseThrow(() -> new AccessDeniedException("Unable to resolve user from token"));
        String merchantCode = user.getMerchant() == null ? null : user.getMerchant().getMerchantCode();
        if (StringUtils.isBlank(merchantCode)) {
            // A blank code is "no filter" to the merchant predicate — refuse rather than widen.
            throw new AccessDeniedException("User is not attached to a merchant");
        }
        return LoanReadScope.originator(merchantCode, user.getId());
    }
}
