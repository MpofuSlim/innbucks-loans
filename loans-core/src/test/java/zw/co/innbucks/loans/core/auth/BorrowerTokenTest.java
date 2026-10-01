package zw.co.innbucks.loans.core.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserGroup;
import zw.co.innbucks.loans.core.user.UserRepository;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A borrower session names a staff member, not a user, carries the BORROWER role and nothing else, and dies the moment
 * that staff member leaves, disappears from the register or changes number: the register is the authority, and a
 * session signed in from a number that is no longer theirs must not outlive the change.
 */
class BorrowerTokenTest {

    private final UserRepository users = mock(UserRepository.class);
    private final StaffMemberRepository members = mock(StaffMemberRepository.class);
    private final JwtProperties properties = properties();
    private final JwtConfig config = new JwtConfig(properties, environment());
    private final JwtService jwtService = new JwtService(config.jwtEncoder(), properties);
    private final JwtDecoder decoder = config.jwtDecoder(new TokenVersionValidator(users, members));

    private final StaffMember chipo = StaffMember.builder().id(2L).employeeNumber("E1012").fullName("Chipo Banda")
            .msisdn("263773456789").employmentStatus(StaffEmploymentStatus.ACTIVE).build();

    {
        when(members.findById(2L)).thenReturn(Optional.of(chipo));
        when(users.findTokenVersionByUsername(anyString())).thenReturn(Optional.of(0L));
    }

    private String borrowerToken() {
        return jwtService.generateBorrowerToken(2L, "E1012", "263773456789", List.of("pin"), 900);
    }

    @Test
    @DisplayName("a borrower token names the staff member, carries BORROWER only, and is read back as a borrower's")
    void claims() {
        Jwt jwt = decoder.decode(borrowerToken());

        assertThat(JwtService.isBorrowerToken(jwt)).isTrue();
        assertThat(jwt.getSubject()).isEqualTo("staff-member:2");
        assertThat(jwt.getClaimAsString("preferred_username")).isEqualTo("borrower:E1012");
        assertThat(jwt.getClaims().get(JwtService.STAFF_MEMBER_CLAIM)).isEqualTo(2L);
        assertThat(jwt.getClaimAsString(JwtService.MSISDN_CLAIM)).isEqualTo("263773456789");
        assertThat(jwt.getClaimAsStringList(JwtService.AUTHENTICATION_METHODS_CLAIM)).containsExactly("pin");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("innbucks-loans");
        assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(900));
        assertThat(new RolesJwtAuthenticationConverter().convert(jwt).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_BORROWER");
        // The user table is not consulted for a borrower: there is no such user.
        verify(users, never()).findTokenVersionByUsername(anyString());
    }

    @Test
    @DisplayName("a staff user's token is not a borrower's")
    void staffTokenIsNotABorrowers() {
        User admin = new User();
        admin.setUsername("admin");
        admin.setExternalSystemId("7f1c2a9e-0000-4000-8000-000000000001");
        admin.setGroups(Set.of(UserGroup.SUPER_ADMIN));

        assertThat(JwtService.isBorrowerToken(decoder.decode(jwtService.generateToken(admin)))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = StaffEmploymentStatus.class, names = {"RESIGNED", "TERMINATED"})
    @DisplayName("the session dies the moment its staff member leaves")
    void leaverRefused(StaffEmploymentStatus status) {
        String token = borrowerToken();
        chipo.setEmploymentStatus(status);

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("no longer a current staff member");
    }

    @Test
    @DisplayName("the session dies when the staff member's number changes, or they are gone from the register")
    void numberChangedOrGone() {
        String token = borrowerToken();

        chipo.setMsisdn("263779999999");
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);

        when(members.findById(2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("a borrower token naming no staff member is refused")
    void noStaffMemberRefused() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("innbucks-loans").issuedAt(now)
                .expiresAt(now.plusSeconds(300)).subject("staff-member:?")
                .claim("realm_access", Map.of("roles", List.of("BORROWER")))
                .claim(JwtService.TOKEN_USE_CLAIM, JwtService.BORROWER_TOKEN_USE)
                .claim(JwtService.STAFF_MEMBER_CLAIM, "two")
                .build();
        String token = config.jwtEncoder().encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("names no staff member");
    }

    private static JwtProperties properties() {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        JwtProperties properties = new JwtProperties();
        properties.setSecret(Base64.getEncoder().encodeToString(bytes));
        return properties;
    }

    private static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("api");
        return environment;
    }
}
