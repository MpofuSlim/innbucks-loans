package zw.co.innbucks.loans.core.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.api.CreateUserRequest;
import zw.co.innbucks.loans.core.commission.CommissionGroupRepository;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.notifications.NotificationService;

import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
            new BCryptPasswordEncoder(4), new Random(7), notificationService, commissionGroupRepository);

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
}
