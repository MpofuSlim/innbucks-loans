package zw.co.reikan.loans.core.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static zw.co.reikan.loans.core.user.UserGroup.*;

/**
 * The group and the merchant of a new user are both caller-chosen, so this matrix
 * is the whole defence against an agent minting a CREDIT_MANAGER for themselves.
 */
class UserGrantPolicyTest {

    private static final String OWN = "merchant-a";
    private static final String OTHER = "merchant-b";

    @Test
    @DisplayName("ONLY BULKIT_ADMIN may grant BULKIT_ADMIN, CREDIT_MANAGER, FINANCE, ORGANISATION_SUPER_USER or RETAIL_SALES")
    void privilegedGroupsAreAdminOnly() {
        Set<UserGroup> privileged = EnumSet.of(BULKIT_ADMIN, CREDIT_MANAGER, FINANCE, ORGANISATION_SUPER_USER, RETAIL_SALES);
        for (UserGroup caller : UserGroup.values()) {
            Set<UserGroup> grantable = UserGrantPolicy.grantableBy(EnumSet.of(caller));
            if (caller == BULKIT_ADMIN) {
                assertThat(grantable).containsAll(privileged);
            } else {
                assertThat(grantable).as("grantable by %s", caller).doesNotContainAnyElementsOf(privileged);
            }
        }
    }

    @Test
    @DisplayName("the matrix: admins anything, super users and retail sales field staff, agents their sub-agents")
    void grantMatrix() {
        assertThat(UserGrantPolicy.grantableBy(EnumSet.of(BULKIT_ADMIN))).isEqualTo(EnumSet.allOf(UserGroup.class));
        assertThat(UserGrantPolicy.grantableBy(EnumSet.of(ORGANISATION_SUPER_USER))).containsExactlyInAnyOrder(AGENTS, SUB_AGENTS);
        assertThat(UserGrantPolicy.grantableBy(EnumSet.of(RETAIL_SALES))).containsExactlyInAnyOrder(AGENTS, SUB_AGENTS);
        assertThat(UserGrantPolicy.grantableBy(EnumSet.of(AGENTS))).containsExactly(SUB_AGENTS);
        assertThat(UserGrantPolicy.grantableBy(EnumSet.of(SUB_AGENTS))).isEmpty();
        assertThat(UserGrantPolicy.grantableBy(EnumSet.of(CREDIT_MANAGER))).isEmpty();
        assertThat(UserGrantPolicy.grantableBy(EnumSet.of(FINANCE))).isEmpty();
    }

    @Test
    @DisplayName("an agent creating a CREDIT_MANAGER — even in their own merchant — is refused")
    void agentCannotMintCreditManager() {
        assertThatThrownBy(() -> UserGrantPolicy.checkMayCreate(EnumSet.of(AGENTS), OWN, CREDIT_MANAGER, OWN))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Not allowed to create a CREDIT_MANAGER user; your role may create: [SUB_AGENTS]");
    }

    @Test
    @DisplayName("a non-admin is confined to their own merchant, compared exactly")
    void nonAdminIsConfinedToOwnMerchant() {
        assertThatThrownBy(() -> UserGrantPolicy.checkMayCreate(EnumSet.of(AGENTS), OWN, SUB_AGENTS, OTHER))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Not allowed to create users in merchant merchant-b: you may only create users in your own merchant");
        assertThatThrownBy(() -> UserGrantPolicy.checkMayCreate(EnumSet.of(ORGANISATION_SUPER_USER), OWN, AGENTS, "MERCHANT-A"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> UserGrantPolicy.checkMayCreate(EnumSet.of(RETAIL_SALES), OWN, AGENTS, OTHER))
                .isInstanceOf(AccessDeniedException.class);

        assertThatCode(() -> UserGrantPolicy.checkMayCreate(EnumSet.of(AGENTS), OWN, SUB_AGENTS, OWN))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("BULKIT_ADMIN may create any group in any merchant")
    void adminIsUnrestricted() {
        for (UserGroup group : UserGroup.values()) {
            assertThatCode(() -> UserGrantPolicy.checkMayCreate(EnumSet.of(BULKIT_ADMIN), OWN, group, OTHER))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("caller groups come from the ROLE_* authorities @PreAuthorize reads; anything else is ignored")
    void callerGroupsFromAuthorities() {
        assertThat(UserGrantPolicy.callerGroups(List.of(
                new SimpleGrantedAuthority("ROLE_AGENTS"),
                new SimpleGrantedAuthority("ROLE_RETAIL_SALES"),
                new SimpleGrantedAuthority("SCOPE_read"),
                new SimpleGrantedAuthority("CREDIT_MANAGER"),
                new SimpleGrantedAuthority("ROLE_UNKNOWN"))))
                .containsExactlyInAnyOrder(AGENTS, RETAIL_SALES);
    }
}
