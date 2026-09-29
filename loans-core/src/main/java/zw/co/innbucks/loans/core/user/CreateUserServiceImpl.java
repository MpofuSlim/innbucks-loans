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
import zw.co.innbucks.loans.core.notifications.NotificationService;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

import static zw.co.innbucks.loans.core.StartupTask.ZERO_BASED_DEFAULT;
import static zw.co.innbucks.loans.core.commission.CommissionStructure.MERCHANT_DEFINED;

@Service
@RequiredArgsConstructor
public class CreateUserServiceImpl implements CreateUserService {

    private static final char[] SPECIAL_CHARACTERS = {'#', '@', '$', '%', '&', '*', '!'};
    private static final char[] NUMERIC_CHARACTERS = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
    private static final String[] ALPHA_UPPER_CHARACTERS = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L",
            "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"};


    // No colons: the SMS gateway refuses ! : / ? " * ; in a body.
    public static final String PASSWORD_SMS_TEMPLATE = """
            %s, your account is ready. Username %s, Temp password %s. Please change password after login.""";

    private static final Logger logger = LoggerFactory.getLogger(CreateUserServiceImpl.class);
    private final MerchantRepository merchantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Random random;
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
            return commissionGroupRepository.findById(request.getCommissionGroupId()).orElseThrow();
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
        String generatedPassword = generatePassword();
        user.setPassword(passwordEncoder.encode(generatedPassword));
        user.setTemporaryPassword(true);
        // A new password ends the sessions minted under the old one.
        user.bumpTokenVersion();
        userRepository.save(user);

        notifyUser(user.getFirstName(), user.getUsername(), user.getMobileNumber(), generatedPassword);
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

        String generatedPassword = generatePassword();

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

        notifyUser(savedUser.getFirstName(), savedUser.getUsername(), savedUser.getMobileNumber(), generatedPassword);
        logger.info("Created user {} with id {}", createUserRequest.getUsername(), savedUser.getId());
        return UserResponse.from(savedUser);
    }

    private void notifyUser(String firstName, String username, String mobileNumber, String generatedPassword) {
        String message = String.format(PASSWORD_SMS_TEMPLATE, firstName, username, generatedPassword);
        notificationService.sendSms(MsisdnUtils.formatMsisdnInternational(mobileNumber), message);
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

    private String generatePassword() {

        StringBuilder passwordBuilder = new StringBuilder(8);

        IntStream.range(0, 3).forEach(index -> {
            int nextSpecialCharacterIndex = random.nextInt(SPECIAL_CHARACTERS.length);
            passwordBuilder.append(SPECIAL_CHARACTERS[nextSpecialCharacterIndex]);
        });
        IntStream.range(0, 3).forEach(index -> {
            int nextUpperCharacterIndex = random.nextInt(ALPHA_UPPER_CHARACTERS.length);
            passwordBuilder.append(ALPHA_UPPER_CHARACTERS[nextUpperCharacterIndex]);
        });
        IntStream.range(0, 3).forEach(index -> {
            int nextNumericCharacterIndex = random.nextInt(NUMERIC_CHARACTERS.length);
            passwordBuilder.append(NUMERIC_CHARACTERS[nextNumericCharacterIndex]);
        });
        int remainingCharacters = 8 - passwordBuilder.length();
        for (int i = 0; i < remainingCharacters; i++) {
            int nextUpperCharacterIndex = random.nextInt(ALPHA_UPPER_CHARACTERS.length);
            passwordBuilder.append(ALPHA_UPPER_CHARACTERS[nextUpperCharacterIndex].toLowerCase());
        }
        swapCharacters(passwordBuilder);
        return passwordBuilder.toString();
    }

    private void swapCharacters(StringBuilder passwordBuilder) {
        int firstRandomIndex = random.nextInt(passwordBuilder.length());
        int secondRandomIndex = random.nextInt(passwordBuilder.length());
        char charAtFirstRandomIndex = passwordBuilder.charAt(firstRandomIndex);
        char charAtSecondRandomIndex = passwordBuilder.charAt(secondRandomIndex);
        passwordBuilder.setCharAt(firstRandomIndex, charAtSecondRandomIndex);
        passwordBuilder.setCharAt(secondRandomIndex, charAtFirstRandomIndex);
    }

}
