package zw.co.innbucks.loans.core.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.api.CreateUserRequest;
import zw.co.innbucks.loans.core.api.ForgotPasswordRequest;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionGroupRepository;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.notifications.NotificationService;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A user's commission group and mobile number, refused in the service as well as at the web edge: an unknown group
 * used to be a bare {@code orElseThrow()} (a 500), and any string at all was stored as 263 plus its last nine
 * characters. Both are refused before anything is saved or sent.
 */
class CreateUserServiceImplTest {

    private final MerchantRepository merchantRepository = mock(MerchantRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CommissionGroupRepository commissionGroupRepository = mock(CommissionGroupRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final CreateUserServiceImpl service = new CreateUserServiceImpl(merchantRepository, userRepository,
            new BCryptPasswordEncoder(4), new PortalCredentialMessages(new PortalProperties()), notificationService,
            commissionGroupRepository);

    @BeforeEach
    void setUp() {
        when(merchantRepository.findByMerchantCode("harare-motors")).thenReturn(Optional.of(Merchant.builder()
                .merchantCode("harare-motors").commissionStructure(CommissionStructure.AGENT_DEFINED).build()));
    }

    private static CreateUserRequest request(String mobileNumber, long commissionGroupId) {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("tmoyo");
        request.setFirstName("Tendai");
        request.setLastName("Moyo");
        request.setMobileNumber(mobileNumber);
        request.setIdNumber("63-2345678-B-42");
        request.setGroup(UserGroup.AGENTS);
        request.setCommissionGroupId(commissionGroupId);
        return request;
    }

    @Test
    @DisplayName("an unknown commission group is the caller's mistake, named as merchant creation names it")
    void unknownCommissionGroup() {
        assertThatThrownBy(() -> service.create(request("0772123123", 999), "harare-motors"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Commission group 999 not found");
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("a mobile number that is not a Zimbabwean mobile is refused, not cut down to its last nine characters")
    void notAMobileNumber() {
        assertThatThrownBy(() -> service.create(request("12345", 0), "harare-motors"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("User mobile number " + MsisdnUtils.ZIMBABWE_MOBILE_MESSAGE);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationService, commissionGroupRepository);
    }

    @Test
    @DisplayName("the new user is sent the password that was stored, by WhatsApp first and SMS as the fallback,"
            + " each worded for its channel")
    void newUserIsToldTheirCredentials() {
        when(commissionGroupRepository.findByNameIgnoreCase(any())).thenReturn(Optional.of(new CommissionGroup()));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(request("0772123123", 0), "harare-motors");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        ArgumentCaptor<String> whatsApp = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sms = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendPrivate(eq("263772123123"), whatsApp.capture(), sms.capture());
        Matcher password = Pattern.compile("temporary password is ([A-Za-z0-9-]{11})\\.").matcher(sms.getValue());
        assertThat(password.find()).isTrue();
        assertThat(new BCryptPasswordEncoder(4).matches(password.group(1), saved.getValue().getPassword())).isTrue();
        assertThat(saved.getValue().getTemporaryPassword()).isTrue();
        assertThat(sms.getValue()).isEqualTo("Hi Tendai, your InnBucks Lending account is ready. Your username"
                + " is tmoyo and your temporary password is " + password.group(1) + ". Please sign in and change it"
                + " immediately.");
        assertThat(whatsApp.getValue()).contains("Username: tmoyo\nTemporary password: " + password.group(1) + "\n");
    }

    @Test
    @DisplayName("a forgotten password is replaced and the new one sent as a reset, with a line to report one not"
            + " asked for; an unknown username sends nothing")
    void forgottenPassword() {
        User user = new User();
        user.setUsername("tmoyo");
        user.setFirstName("Tendai");
        user.setMobileNumber("263772123123");
        when(userRepository.findByUsername("tmoyo")).thenReturn(Optional.of(user));

        service.resetPassword(new ForgotPasswordRequest("tmoyo"));
        service.resetPassword(new ForgotPasswordRequest("nobody"));

        ArgumentCaptor<String> sms = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendPrivate(eq("263772123123"), anyString(), sms.capture());
        assertThat(sms.getValue()).startsWith("Hi Tendai, your InnBucks Lending password has been reset. Your"
                + " username is tmoyo and your temporary password is ")
                .endsWith(". Please sign in and change it immediately. If you did not ask for this, tell your"
                        + " administrator.");
        assertThat(user.getTemporaryPassword()).isTrue();
        verifyNoMoreInteractions(notificationService);
    }
}
