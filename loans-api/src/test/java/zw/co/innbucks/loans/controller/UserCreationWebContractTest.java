package zw.co.innbucks.loans.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.auth.RolesJwtAuthenticationConverter;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionGroupRepository;
import zw.co.innbucks.loans.core.commission.CommissionStructure;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantMapperImpl;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.merchant.MerchantService;
import zw.co.innbucks.loans.core.notifications.NotificationService;
import zw.co.innbucks.loans.core.user.CreateUserServiceImpl;
import zw.co.innbucks.loans.core.user.FindUserService;
import zw.co.innbucks.loans.core.user.User;
import zw.co.innbucks.loans.core.user.UserRepository;
import zw.co.innbucks.loans.web.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /merchants/{merchantCode}/users} over the REAL {@link CreateUserServiceImpl} and {@link MerchantService},
 * with only the repositories and the SMS sender mocked: what a caller's mistakes are answered with, and what is
 * stored. Who may call it is {@link AdminEndpointsAuthorizationTest}'s subject; no database, no Spring context.
 */
class UserCreationWebContractTest {

    private static final String MERCHANT_CODE = "harare-motors";

    private MerchantRepository merchantRepository;
    private UserRepository userRepository;
    private CommissionGroupRepository commissionGroupRepository;
    private NotificationService notificationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        merchantRepository = mock(MerchantRepository.class);
        userRepository = mock(UserRepository.class);
        commissionGroupRepository = mock(CommissionGroupRepository.class);
        notificationService = mock(NotificationService.class);
        when(merchantRepository.findByMerchantCode(MERCHANT_CODE)).thenReturn(Optional.of(Merchant.builder()
                .merchantCode(MERCHANT_CODE).companyName("Harare Motor Spares")
                .commissionStructure(CommissionStructure.AGENT_DEFINED).build()));
        when(commissionGroupRepository.findById(3L)).thenReturn(Optional.of(CommissionGroup.builder()
                .name("80-20-Favouring-InnBucks").agentCommission(new BigDecimal("20.0"))
                .providerCommission(new BigDecimal("80.0")).percentage(true).build()));
        when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FindUserService findUserService = mock(FindUserService.class);
        User admin = new User();
        admin.setUsername("admin");
        admin.setMerchant(Merchant.builder().merchantCode(Merchant.DEFAULT_MERCHANT_CODE).build());
        when(findUserService.resolveUserFromAccessToken(any())).thenReturn(Optional.of(admin));

        MerchantService merchantService = new MerchantService(merchantRepository, new MerchantMapperImpl(),
                commissionGroupRepository, mock(AuditService.class));
        CreateUserServiceImpl createUserService = new CreateUserServiceImpl(merchantRepository, userRepository,
                new BCryptPasswordEncoder(4), new Random(7), notificationService, commissionGroupRepository);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new MerchantController(merchantService, mock(AuthService.class),
                        createUserService, findUserService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private static RequestPostProcessor asSuperAdmin() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
                .subject("admin-sub")
                .claim("preferred_username", "admin")
                .claim("realm_access", Map.of("roles", List.of("SUPER_ADMIN")))
                .build();
        JwtAuthenticationToken authentication =
                (JwtAuthenticationToken) new RolesJwtAuthenticationConverter().convert(jwt);
        return request -> {
            request.setUserPrincipal(authentication);
            return request;
        };
    }

    private static String newUser(String mobileNumber, long commissionGroupId) {
        return """
                {"username": "tmoyo", "firstName": "Tendai", "lastName": "Moyo", "mobileNumber": "%s",
                 "idNumber": "63-2345678-B-42", "group": "AGENTS", "commissionGroupId": %d}
                """.formatted(mobileNumber, commissionGroupId);
    }

    @Test
    @DisplayName("an unknown commission group → 400 INVALID_REQUEST naming it, as creating a merchant answers;"
            + " nothing saved, no SMS")
    void unknownCommissionGroupIs400() throws Exception {
        mvc.perform(post("/lending/v1/merchants/{code}/users", MERCHANT_CODE).with(asSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(newUser("0772123123", 999)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Commission group 999 not found"));
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationService);

        // The merchant path's answer to the same mistake, which this one now matches.
        mvc.perform(post("/lending/v1/merchants").with(asSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"merchantCode": "bulawayo-traders", "companyName": "Bulawayo Traders",
                                 "disbursementType": "CUSTOMER_MOBILE_WALLET", "commissionStructure": "MERCHANT_DEFINED",
                                 "commissionGroupId": 999}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Commission group 999 not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "0772123", "07721231234", "0762123123", "+263 772 123 123", "077212312a",
            "+268772123123"})
    @DisplayName("a mobile number that is not a Zimbabwean mobile → 400 VALIDATION_ERROR on the field; nothing saved")
    void notAMobileNumberIs400(String mobileNumber) throws Exception {
        mvc.perform(post("/lending/v1/merchants/{code}/users", MERCHANT_CODE).with(asSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(mobileNumber, 3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.data.mobileNumber").value(MsisdnUtils.ZIMBABWE_MOBILE_MESSAGE));
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0772123123", "772123123", "263772123123", "+263772123123"})
    @DisplayName("every spelling of a Zimbabwean mobile is accepted and stored as 263772123123, where the SMS goes")
    void mobileNumberIsNormalised(String mobileNumber) throws Exception {
        mvc.perform(post("/lending/v1/merchants/{code}/users", MERCHANT_CODE).with(asSuperAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(newUser(mobileNumber, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mobileNumber").value("263772123123"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getMobileNumber()).isEqualTo("263772123123");
        verify(notificationService).sendSms(eq("263772123123"), anyString());
    }

    @Test
    @DisplayName("every mobile prefix an applicant may have is accepted: 71, 73, 77, 78 and 79")
    void everyMobilePrefix() throws Exception {
        for (String prefix : List.of("71", "73", "77", "78", "79")) {
            mvc.perform(post("/lending/v1/merchants/{code}/users", MERCHANT_CODE).with(asSuperAdmin())
                            .contentType(MediaType.APPLICATION_JSON).content(newUser("0" + prefix + "2123123", 3)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.mobileNumber").value("263" + prefix + "2123123"));
        }
    }
}
