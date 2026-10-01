package zw.co.innbucks.loans.core.borrower;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.JwtService;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.staff.StaffMemberRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.MIDDLEWARE;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.STRANGER;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.ZW;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.claims;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.properties;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.publicPem;
import static zw.co.innbucks.loans.core.borrower.AssertionFixtures.rs256;

/**
 * Sign-in takes one thing from the assertion, the phone, and who the borrower is from the staff register: a current
 * staff member on that number, or nobody. Every refusal is audited; nothing is minted for one.
 */
class BorrowerSessionServiceTest {

    private final BorrowerProperties properties = properties(publicPem(MIDDLEWARE), "");
    private final MiddlewareAssertionVerifier verifier =
            new MiddlewareAssertionVerifier(properties, ZW, new TestAssertionSigner(properties, ZW));
    private final AssertionUses assertionUses = mock(AssertionUses.class);
    private final StaffMemberRepository members = mock(StaffMemberRepository.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final BorrowerSessionService service =
            new BorrowerSessionService(verifier, assertionUses, members, jwtService, properties, auditService);

    private final StaffMember chipo = StaffMember.builder().id(2L).employeeNumber("E1012").fullName("Chipo Banda")
            .msisdn("263773456789").department("Treasury").employmentStatus(StaffEmploymentStatus.ACTIVE).build();

    {
        when(members.findByMsisdn("263773456789")).thenReturn(Optional.of(chipo));
        when(jwtService.generateBorrowerToken(anyLong(), anyString(), anyString(), any(), anyLong()))
                .thenReturn("borrower-token");
    }

    @Test
    @DisplayName("a current staff member's assertion signs them in for 15 minutes, spent once, audited")
    void signsIn() {
        BorrowerSession session = service.signIn(rs256(MIDDLEWARE, claims().build()));

        assertThat(session.accessToken()).isEqualTo("borrower-token");
        assertThat(session.tokenType()).isEqualTo("Bearer");
        assertThat(session.expiresIn()).isEqualTo(900);
        assertThat(session.employeeNumber()).isEqualTo("E1012");
        assertThat(session.fullName()).isEqualTo("Chipo Banda");
        assertThat(session.toString()).doesNotContain("borrower-token");
        verify(jwtService).generateBorrowerToken(2L, "E1012", "263773456789", List.of("pin"), 900);
        ArgumentCaptor<VerifiedAssertion> spent = ArgumentCaptor.forClass(VerifiedAssertion.class);
        verify(assertionUses).spend(spent.capture(), any(), any());
        assertThat(spent.getValue().jti()).isEqualTo("mw-7c1d9e2a");
        verify(assertionUses).spend(any(), org.mockito.ArgumentMatchers.eq(BorrowerAssertionUse.Purpose.SIGN_IN),
                org.mockito.ArgumentMatchers.eq(2L));
        assertThat(audited()).extracting(AuditLog::getEventType).isEqualTo(BorrowerSessionService.SIGNED_IN);
        assertThat(audited().getActorId()).isEqualTo("borrower:E1012");
        assertThat(audited().getDetail()).contains("methods:pin").contains("assertion:mw-7c1d9e2a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+263773456789", "263773456789", "0773456789", "773456789"})
    @DisplayName("the phone matches the register however the middleware wrote it")
    void phoneSpellings(String phone) {
        assertThat(service.signIn(rs256(MIDDLEWARE, claims().subject(phone).build())).employeeNumber())
                .isEqualTo("E1012");
    }

    @Test
    @DisplayName("the session length follows the setting")
    void sessionLength() {
        properties.setSessionMinutes(5);

        assertThat(service.signIn(rs256(MIDDLEWARE, claims().build())).expiresIn()).isEqualTo(300);
    }

    @ParameterizedTest
    @EnumSource(value = StaffEmploymentStatus.class, names = {"RESIGNED", "TERMINATED"})
    @DisplayName("a staff member who has left is not on the register, and spends nothing")
    void leaverRefused(StaffEmploymentStatus status) {
        chipo.setEmploymentStatus(status);

        assertThatThrownBy(() -> service.signIn(rs256(MIDDLEWARE, claims().build())))
                .isInstanceOf(NotOnStaffRegisterException.class)
                .hasMessage(NotOnStaffRegisterException.MESSAGE);
        verify(assertionUses, never()).spend(any(), any(), any());
        verify(jwtService, never()).generateBorrowerToken(anyLong(), anyString(), anyString(), any(), anyLong());
        assertThat(audited().getEventType()).isEqualTo(BorrowerSessionService.REFUSED);
        assertThat(audited().getEntityId()).isEqualTo("****6789");
    }

    @ParameterizedTest
    @EnumSource(value = StaffEmploymentStatus.class, names = {"SUSPENDED", "UNPAID_LEAVE"})
    @DisplayName("suspended and unpaid-leave staff still sign in: they are employed (what they may borrow is the offer's)")
    void stillEmployedSignsIn(StaffEmploymentStatus status) {
        chipo.setEmploymentStatus(status);

        assertThat(service.signIn(rs256(MIDDLEWARE, claims().build())).employeeNumber()).isEqualTo("E1012");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+263779999999", "+27821234567", "not-a-phone"})
    @DisplayName("a genuine assertion for a number not on the register, or not a Zimbabwean mobile, is refused")
    void unknownPhoneRefused(String phone) {
        assertThatThrownBy(() -> service.signIn(rs256(MIDDLEWARE, claims().subject(phone).build())))
                .isInstanceOf(NotOnStaffRegisterException.class);
        verify(assertionUses, never()).spend(any(), any(), any());
    }

    @Test
    @DisplayName("a forged assertion is refused before the register is read, and audited without a phone")
    void forgedRefused() {
        assertThatThrownBy(() -> service.signIn(rs256(STRANGER, claims().build())))
                .isInstanceOf(AssertionRejectedException.class);
        verify(members, never()).findByMsisdn(anyString());
        assertThat(audited().getEventType()).isEqualTo(BorrowerSessionService.REFUSED);
        assertThat(audited().getEntityId()).isNull();
        assertThat(audited().getDetail()).contains("signature");
    }

    @Test
    @DisplayName("a replayed assertion is refused and audited, and no session is minted")
    void replayRefused() {
        doThrow(new AssertionRejectedException("replayed jti")).when(assertionUses).spend(any(), any(), any());

        assertThatThrownBy(() -> service.signIn(rs256(MIDDLEWARE, claims().build())))
                .isInstanceOf(AssertionRejectedException.class);
        verify(jwtService, never()).generateBorrowerToken(anyLong(), anyString(), anyString(), any(), anyLong());
        assertThat(audited().getDetail()).contains("replayed");
    }

    @Test
    @DisplayName("with no middleware key the server says so (503), without looking at the assertion")
    void unavailable() {
        BorrowerProperties off = new BorrowerProperties();
        BorrowerSessionService unconfigured = new BorrowerSessionService(
                new MiddlewareAssertionVerifier(off, ZW, new TestAssertionSigner(off, ZW)), assertionUses, members,
                jwtService, off, auditService);

        assertThatThrownBy(() -> unconfigured.signIn(rs256(MIDDLEWARE, claims().build())))
                .isInstanceOf(BorrowerSignInUnavailableException.class);
        verify(auditService, never()).record(any());
    }

    @Test
    @DisplayName("the profile is the register's record, with the phone masked")
    void profile() {
        when(members.findById(2L)).thenReturn(Optional.of(chipo));

        BorrowerProfile profile = service.profile(2L, List.of("pin"));

        assertThat(profile).isEqualTo(new BorrowerProfile("E1012", "Chipo Banda", "****6789", "Treasury",
                List.of("pin")));
        when(members.findById(3L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.profile(3L, List.of()))
                .isInstanceOf(NotOnStaffRegisterException.class);
    }

    private AuditLog audited() {
        ArgumentCaptor<AuditLog.AuditLogBuilder> builder = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(auditService, org.mockito.Mockito.atLeastOnce()).record(builder.capture());
        return builder.getValue().build();
    }
}
