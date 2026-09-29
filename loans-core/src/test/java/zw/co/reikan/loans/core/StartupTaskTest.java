package zw.co.reikan.loans.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.merchant.MerchantService;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserGroup;
import zw.co.reikan.loans.core.user.UserRepository;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static zw.co.reikan.loans.core.merchant.Merchant.DEFAULT_MERCHANT_CODE;

/**
 * The bootstrap admin used to fall back to the source-published "#Pass123"
 * wherever BULKIT_PASSWORD was not in the process env — which the deploy
 * workflows never set and a mounted /app/config cannot supply. A deployment
 * with no configured password must now create NO admin (and still boot).
 */
class StartupTaskTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private MerchantRepository merchantRepository;
    private CommissionGroupRepository commissionGroupRepository;
    private MockEnvironment environment;
    private StartupTask task;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        merchantRepository = mock(MerchantRepository.class);
        commissionGroupRepository = mock(CommissionGroupRepository.class);
        environment = new MockEnvironment();
        // What the packaged application.yml resolves to when BULKIT_PASSWORD is unset.
        environment.setProperty("bootstrap.admin.password", "");

        // Seed data already present, so run() goes straight to the admin step.
        when(commissionGroupRepository.findByNameIgnoreCase(anyString()))
                .thenReturn(Optional.of(CommissionGroup.builder().name("existing").enabled(true).build()));
        when(merchantRepository.existsByMerchantCode(DEFAULT_MERCHANT_CODE)).thenReturn(true);
        when(merchantRepository.findByMerchantCode(DEFAULT_MERCHANT_CODE))
                .thenReturn(Optional.of(Merchant.builder().merchantCode(DEFAULT_MERCHANT_CODE).build()));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("{bcrypt}hash");

        task = taskWith(environment);
    }

    private StartupTask taskWith(MockEnvironment env) {
        return new StartupTask(mock(MerchantService.class), commissionGroupRepository, merchantRepository,
                userRepository, passwordEncoder, env);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "api", "api,dummy-loan-approval"})
    @DisplayName("deployment profile set + blank password → NO admin created, and the boot carries on")
    void deploymentWithBlankPasswordCreatesNoAdmin(String profiles) throws Exception {
        environment.setActiveProfiles(StringUtils.commaDelimitedListToStringArray(profiles));

        task.run();

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("deployment + bootstrap.admin.password (e.g. from /app/config) → admin created with THAT password")
    void deploymentWithConfiguredPasswordCreatesAdmin() throws Exception {
        environment.setActiveProfiles("api");
        environment.setProperty("bootstrap.admin.password", "operator-chosen-pw");

        task.run();

        verify(passwordEncoder).encode("operator-chosen-pw");
        verify(passwordEncoder, never()).encode("#Pass123");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("admin");
        assertThat(saved.getValue().getGroups()).containsExactly(UserGroup.BULKIT_ADMIN);
        assertThat(saved.getValue().getTemporaryPassword()).isTrue();
    }

    @Test
    @DisplayName("deployment + only the BULKIT_PASSWORD env var (packaged yml replaced by /app/config) → still used")
    void deploymentReadsBulkitPasswordDirectly() throws Exception {
        MockEnvironment withoutPackagedYml = new MockEnvironment();
        withoutPackagedYml.getPropertySources().addLast(processEnv(Map.of("BULKIT_PASSWORD", "env-supplied-pw")));
        withoutPackagedYml.setActiveProfiles("api");

        taskWith(withoutPackagedYml).run();

        verify(passwordEncoder).encode("env-supplied-pw");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("dev profile + blank password → admin created with the development fallback")
    void devProfileFallsBackToDevPassword() throws Exception {
        environment.setActiveProfiles("api", "dev");

        task.run();

        verify(passwordEncoder).encode("#Pass123");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("an admin that already exists is left untouched — no password is resolved at all")
    void existingAdminIsUntouched() throws Exception {
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(new User()));

        task.run();

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("the BOOTSTRAP_ADMIN_USERNAME env var still names the account, now via bootstrap.admin.username")
    void usernameEnvVarStillBinds() throws Exception {
        environment.setActiveProfiles("api");
        environment.getPropertySources().addLast(processEnv(Map.of("BOOTSTRAP_ADMIN_USERNAME", "root")));
        environment.setProperty("bootstrap.admin.password", "operator-chosen-pw");
        when(userRepository.findByUsername("root")).thenReturn(Optional.empty());

        task.run();

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("root");
    }

    /** Resolves relaxed names the way the real process environment does. */
    private static SystemEnvironmentPropertySource processEnv(Map<String, Object> variables) {
        return new SystemEnvironmentPropertySource("processEnv", variables);
    }
}
