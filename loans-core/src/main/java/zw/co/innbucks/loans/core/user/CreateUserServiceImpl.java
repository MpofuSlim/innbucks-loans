package zw.co.innbucks.loans.core.user;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.TextUtils;
import zw.co.innbucks.loans.core.api.*;
import zw.co.innbucks.loans.core.commission.CommissionGroup;
import zw.co.innbucks.loans.core.commission.CommissionGroupRepository;
import zw.co.innbucks.loans.core.exception.DuplicateUserByUsernameException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.merchant.MerchantRepository;
import zw.co.innbucks.loans.core.merchant.MerchantService;
import zw.co.innbucks.loans.core.notifications.NotificationService;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static zw.co.innbucks.loans.core.StartupTask.ZERO_BASED_DEFAULT;
import static zw.co.innbucks.loans.core.commission.CommissionStructure.MERCHANT_DEFINED;

@Service
@RequiredArgsConstructor
public class CreateUserServiceImpl implements CreateUserService {

    private static final Logger logger = LoggerFactory.getLogger(CreateUserServiceImpl.class);
    private final MerchantRepository merchantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PortalCredentialMessages messages;
    private final NotificationService notificationService;
    private final CommissionGroupRepository commissionGroupRepository;

    @Transactional
    public UserResponse create(CreateUserRequest createAgentRequest, String merchantCode) {
        NewUser request = NewUser.builder()
                .email(createAgentRequest.getEmail())
                .groups(List.of(createAgentRequest.getGroup()))
                .idNumber(createAgentRequest.getIdNumber())
                .mobileNumber(createAgentRequest.getMobileNumber())
                .username(createAgentRequest.getUsername())
                .firstName(createAgentRequest.getFirstName())
                .lastName(createAgentRequest.getLastName())
                .merchantCode(merchantCode)
                .commissionGroupId(createAgentRequest.getCommissionGroupId())
                .physicalAddress(createAgentRequest.getPhysicalAddress())
                .build();
        return create(request);
    }

    private CommissionGroup resolveCommissionGroup(NewUser request, Merchant merchant) {
        if (MERCHANT_DEFINED == merchant.getCommissionStructure()) {
            return merchant.getCommissionGroup();
        }

        if (ObjectUtils.isEmpty(request.getCommissionGroupId())) {
            throw new ValidationException("Commission group id is required");
        }

        if (request.getCommissionGroupId() > 0) {
            // A bare orElseThrow() made an unknown id a 500; merchant creation has always answered it as a 400.
            return MerchantService.requireCommissionGroup(commissionGroupRepository, request.getCommissionGroupId());
        }
        return commissionGroupRepository.findByNameIgnoreCase(ZERO_BASED_DEFAULT).orElseThrow();
    }

    /**
     * An unknown username is logged and otherwise ignored, so the endpoint answers the same either
     * way and cannot be used to find out which usernames exist.
     */
    @Override
    public void resetPassword(ForgotPasswordRequest request) {
        Optional<User> account = userRepository.findByUsername(request.getUsername());
        if (account.isEmpty()) {
            logger.info("Forgot-password request for an unknown username; nothing sent");
            return;
        }
        User user = account.get();
        String generatedPassword = TemporaryPasswordGenerator.generate();
        user.setPassword(passwordEncoder.encode(generatedPassword));
        user.setTemporaryPassword(true);
        // A new password ends the sessions minted under the old one.
        user.bumpTokenVersion();
        userRepository.save(user);

        notifyUser(PortalCredentialMessages.Reason.SELF_SERVICE_RESET, user, generatedPassword);
    }

    private UserResponse create(NewUser createUserRequest) {
        logger.info("Creating user {}", createUserRequest.getUsername());

        validateRequest(createUserRequest);

        Optional<User> userByUsername = userRepository.findByUsername(createUserRequest.getUsername());
        if (userByUsername.isPresent()) {
            throw new DuplicateUserByUsernameException(createUserRequest.getUsername());
        }

        Merchant merchant = merchantRepository.findByMerchantCode(createUserRequest.getMerchantCode())
                .orElseThrow(() -> new NotFoundException("Merchant " + createUserRequest.getMerchantCode() + " not found"));

        CommissionGroup commissionGroup = resolveCommissionGroup(createUserRequest, merchant);

        String generatedPassword = TemporaryPasswordGenerator.generate();

        User user = new User();
        user.setExternalSystemId(UUID.randomUUID().toString());
        user.setMerchant(merchant);
        user.setTemporaryPassword(true);
        user.setUsername(createUserRequest.getUsername());
        user.setPassword(passwordEncoder.encode(generatedPassword));
        user.setFirstName(createUserRequest.getFirstName());
        user.setLastName(createUserRequest.getLastName());
        user.setEmail(createUserRequest.getEmail());
        user.setMobileNumber(MsisdnUtils.formatMsisdnInternational(createUserRequest.getMobileNumber()));
        user.setIdNumber(TextUtils.trimSpecialCharacters(createUserRequest.getIdNumber()).toUpperCase());
        user.setGroups(createUserRequest.getGroups() == null ? new HashSet<>()
                : new HashSet<>(createUserRequest.getGroups()));
        user.setCommissionGroup(commissionGroup);
        user.setPhysicalAddress(createUserRequest.getPhysicalAddress());

        User savedUser = userRepository.save(user);

        notifyUser(PortalCredentialMessages.Reason.ACCOUNT_CREATED, savedUser, generatedPassword);
        logger.info("Created user {} with id {}", createUserRequest.getUsername(), savedUser.getId());
        return UserResponse.from(savedUser);
    }

    /** By WhatsApp, or by SMS when WhatsApp fails; never logged. */
    private void notifyUser(PortalCredentialMessages.Reason reason, User user, String generatedPassword) {
        notificationService.sendPrivate(user.getMobileNumber(),
                messages.whatsApp(reason, user.getFirstName(), user.getUsername(), generatedPassword),
                messages.sms(reason, user.getFirstName(), user.getUsername(), generatedPassword));
    }

    private void validateRequest(NewUser createUserRequest) {

        if (!StringUtils.hasText(createUserRequest.getUsername())) {
            throw new ValidationException("Username is required");
        }
        if (!StringUtils.hasText(createUserRequest.getFirstName())) {
            throw new ValidationException("User first name is required");
        }
        if (!StringUtils.hasText(createUserRequest.getLastName())) {
            throw new ValidationException("User last name is required");
        }
        if (!StringUtils.hasText(createUserRequest.getMobileNumber())) {
            throw new ValidationException("User mobile number is required");
        }
        // The request is validated at the web edge; checked here too because what is stored below is 263 plus
        // the LAST NINE characters, which turns any string at all into a well-formed but wrong number.
        if (!createUserRequest.getMobileNumber().matches(MsisdnUtils.ZIMBABWE_MOBILE_REGEX)) {
            throw new ValidationException("User mobile number " + MsisdnUtils.ZIMBABWE_MOBILE_MESSAGE);
        }
        if (!StringUtils.hasText(createUserRequest.getIdNumber())) {
            throw new ValidationException("User id number is required");
        }
        if (createUserRequest.getMerchantCode() == null) {
            throw new ValidationException("User branch id is required");
        }
        if (createUserRequest.getGroups() == null || createUserRequest.getGroups().isEmpty()) {
            throw new ValidationException("User groups required");
        }
    }
}
