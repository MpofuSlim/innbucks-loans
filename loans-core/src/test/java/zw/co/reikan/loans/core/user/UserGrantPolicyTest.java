package zw.co.reikan.loans.core.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.EnumSet;
import java.util.List;

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
    @DisplayName("only BULKIT_ADMIN grants groups: every other group grants nothing")
    void grantMatrix() {
        assertThat(UserGrantPolicy.grantableBy(EnumSet.of(BULKIT_ADMIN))).isEqualTo(EnumSet.allOf(UserGroup.class));
        for (UserGroup caller : EnumSet.complementOf(EnumSet.of(BULKIT_ADMIN))) {
            assertThat(UserGrantPolicy.grantableBy(EnumSet.of(caller))).as("grantable by %s", caller).isEmpty();
        }
    }

    @Test
    @DisplayName("an agent creating a user of any group, even in their own merchant, is refused")
    void agentCannotCreateUsers() {
        assertThatThrownBy(() -> UserGrantPolicy.checkMayCreate(EnumSet.of(AGENTS), OWN, CREDIT_MANAGER, OWN))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Not allowed to create a CREDIT_MANAGER user; your role may create: []");
        assertThatThrownBy(() -> UserGrantPolicy.checkMayCreate(EnumSet.of(CREDIT_MANAGER, FINANCE), OWN, AGENTS, OWN))
                .isInstanceOf(AccessDeniedException.class);
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
                new SimpleGrantedAuthority("ROLE_FINANCE"),
                new SimpleGrantedAuthority("SCOPE_read"),
                new SimpleGrantedAuthority("CREDIT_MANAGER"),
                new SimpleGrantedAuthority("ROLE_UNKNOWN"))))
                .containsExactlyInAnyOrder(AGENTS, FINANCE);
    }
}
