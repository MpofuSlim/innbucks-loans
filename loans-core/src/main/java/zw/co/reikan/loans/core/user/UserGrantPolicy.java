package zw.co.reikan.loans.core.user;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static zw.co.reikan.loans.core.user.UserGroup.*;

/**
 * Which groups a caller may hand out when creating a user, and in which merchant.
 * Both come from the request (group in the body, merchant in the path), so without
 * this any authenticated caller could mint a CREDIT_MANAGER and approve their own
 * loans, or plant a user in someone else's merchant.
 *
 * <p>The matrix follows how the rest of the API already treats each role:</p>
 * <ul>
 *   <li>{@code BULKIT_ADMIN} — the platform owner: any group, in any merchant.</li>
 *   <li>{@code ORGANISATION_SUPER_USER}, {@code RETAIL_SALES} — onboard field staff
 *       ({@code AGENTS}, {@code SUB_AGENTS}), in their own merchant.</li>
 *   <li>{@code AGENTS} — their own sales consultants ({@code SUB_AGENTS}), in their
 *       own merchant.</li>
 * </ul>
 * <p>Every other group is BULKIT_ADMIN's alone to grant: CREDIT_MANAGER approves loans,
 * FINANCE and ORGANISATION_SUPER_USER carry merchant-wide authority, and RETAIL_SALES
 * reads every merchant's loans — each is more than any other grantor holds.</p>
 */
public final class UserGrantPolicy {

    private static final String ROLE_PREFIX = "ROLE_";

    private static final Map<UserGroup, Set<UserGroup>> GRANTABLE = new EnumMap<>(UserGroup.class);

    static {
        GRANTABLE.put(BULKIT_ADMIN, EnumSet.allOf(UserGroup.class));
        GRANTABLE.put(ORGANISATION_SUPER_USER, EnumSet.of(AGENTS, SUB_AGENTS));
        GRANTABLE.put(RETAIL_SALES, EnumSet.of(AGENTS, SUB_AGENTS));
        GRANTABLE.put(AGENTS, EnumSet.of(SUB_AGENTS));
    }

    private UserGrantPolicy() {
    }

    /**
     * The caller's groups, read from the same {@code ROLE_*} authorities that
     * {@code @PreAuthorize} evaluates, so the two checks can never disagree.
     */
    public static Set<UserGroup> callerGroups(Collection<? extends GrantedAuthority> authorities) {
        Set<UserGroup> groups = EnumSet.noneOf(UserGroup.class);
        for (UserGroup group : UserGroup.values()) {
            if (authorities.stream().anyMatch(a -> (ROLE_PREFIX + group.name()).equals(a.getAuthority()))) {
                groups.add(group);
            }
        }
        return groups;
    }

    public static Set<UserGroup> grantableBy(Set<UserGroup> callerGroups) {
        Set<UserGroup> grantable = EnumSet.noneOf(UserGroup.class);
        callerGroups.forEach(group -> grantable.addAll(GRANTABLE.getOrDefault(group, Set.of())));
        return grantable;
    }

    /**
     * @throws AccessDeniedException (403) when the requested group is outside the
     *         caller's allow-list, or a non-admin targets a merchant other than their own.
     */
    public static void checkMayCreate(Set<UserGroup> callerGroups, String callerMerchantCode,
                                      UserGroup requestedGroup, String targetMerchantCode) {
        Set<UserGroup> grantable = grantableBy(callerGroups);
        if (!grantable.contains(requestedGroup)) {
            throw new AccessDeniedException("Not allowed to create a " + requestedGroup
                    + " user; your role may create: " + grantable);
        }
        // Exact match: merchant lookups are case-sensitive, so a case-insensitive
        // compare would let "abc" reach a distinct merchant "ABC".
        if (!callerGroups.contains(BULKIT_ADMIN) && !targetMerchantCode.equals(callerMerchantCode)) {
            throw new AccessDeniedException("Not allowed to create users in merchant " + targetMerchantCode
                    + ": you may only create users in your own merchant");
        }
    }
}
