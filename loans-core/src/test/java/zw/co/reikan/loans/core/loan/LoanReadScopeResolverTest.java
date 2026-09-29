package zw.co.reikan.loans.core.loan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.user.FindUserServiceImpl;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The role → scope table behind {@code /api/loans/search} and {@code /api/loans/{id}}.
 * Runs over the real {@link FindUserServiceImpl}, so the roles are read from the
 * token's {@code realm_access.roles} exactly as in production.
 */
class LoanReadScopeResolverTest {

    private UserRepository userRepository;
    private LoanReadScopeResolver resolver;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        resolver = new LoanReadScopeResolver(new FindUserServiceImpl(userRepository));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BULKIT_ADMIN", "CREDIT_MANAGER", "FINANCE"})
    @DisplayName("lender-side staff read every merchant's loans, without a user lookup")
    void lenderStaffArePlatformWide(String role) {
        assertThat(resolver.resolve(token("staff", role))).isEqualTo(LoanReadScope.platform());
        verifyNoInteractions(userRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORGANISATION_SUPER_USER", "RETAIL_SALES"})
    @DisplayName("merchant management reads its own merchant's loans, every originator")
    void merchantManagementIsMerchantWide(String role) {
        givenUser("manager", 3L, "M-001");
        assertThat(resolver.resolve(token("manager", role))).isEqualTo(LoanReadScope.merchant("M-001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AGENTS", "SUB_AGENTS"})
    @DisplayName("agents read only the loans they originated, within their merchant")
    void agentsSeeOnlyWhatTheyOriginated(String role) {
        givenUser("agent.jane", 7L, "M-001");
        assertThat(resolver.resolve(token("agent.jane", role)))
                .isEqualTo(LoanReadScope.originator("M-001", 7L));
    }

    @Test
    @DisplayName("a role the table does not know falls to the NARROWEST scope, never the widest")
    void unknownRoleIsOriginatorScoped() {
        givenUser("someone", 9L, "M-001");
        assertThat(resolver.resolve(token("someone"))).isEqualTo(LoanReadScope.originator("M-001", 9L));
    }

    @Test
    @DisplayName("a platform role wins over a merchant role held alongside it")
    void broadestHeldRoleWins() {
        assertThat(resolver.resolve(token("both", "AGENTS", "CREDIT_MANAGER"))).isEqualTo(LoanReadScope.platform());
    }

    @Test
    @DisplayName("a token that resolves to no user is refused, not widened")
    void unresolvableUserIsRefused() {
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve(token("ghost", "AGENTS")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a user with no merchant code is refused — a blank code would mean \"no merchant filter\"")
    void blankMerchantCodeIsRefused() {
        givenUser("orphan", 11L, " ");
        assertThatThrownBy(() -> resolver.resolve(token("orphan", "ORGANISATION_SUPER_USER")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a narrowed scope cannot even be built without a merchant code")
    void narrowedScopeRequiresMerchantCode() {
        assertThatThrownBy(() -> LoanReadScope.originator(null, 7L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LoanReadScope.merchant("")).isInstanceOf(IllegalArgumentException.class);
    }

    private void givenUser(String username, Long id, String merchantCode) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setMerchant(Merchant.builder().merchantCode(merchantCode).build());
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
    }

    private static Jwt token(String username, String... roles) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("preferred_username", username)
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
    }
}
