package zw.co.reikan.loans.core.user;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.MsisdnUtil;
import zw.co.reikan.loans.core.Utils;
import zw.co.reikan.loans.core.api.*;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.exception.DuplicateUserByUsernameException;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.keycloak.KeycloakService;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.merchant.MerchantMapper;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.notifications.NotificationService;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.IntStream;

import static zw.co.reikan.loans.core.commission.CommissionStructure.MERCHANT_DEFINED;

@Service
@RequiredArgsConstructor
public class CreateUserServiceImpl implements CreateUserService {

    private static final char[] SPECIAL_CHARACTERS = {'#', '@', '$', '%', '&', '*', '!'};
    private static final char[] NUMERIC_CHARACTERS = {'1', '2', '3', '4', '5', '6', '7', '8', '9'};
    private static final String[] ALPHA_UPPER_CHARACTERS = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L",
            "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"};


    private static final String PASSWORD_SMS_TEMPLATE = """
            %s, your account is ready. Username: %s, Temp password: %s. Please change password after login.""";

    private static final Logger logger = LoggerFactory.getLogger(CreateUserServiceImpl.class);
    private final MerchantRepository merchantRepository;
    private final UserRepository userRepository;
    private final KeycloakService keycloakService;
    private final Random random;
    private final MerchantMapper merchantMapper;
    private final NotificationService notificationService;
    private final CommissionGroupRepository commissionGroupRepository;

    @Transactional
    public CreateUserResponse create(CreateAgentRequest createAgentRequest, User parentAgent, String merchantCode) {
        CreateUserRequest request = CreateUserRequest.builder()
                .email(createAgentRequest.getEmail())
                .groups(List.of(createAgentRequest.getGroup()))
                .idNumber(createAgentRequest.getIdNumber())
                .mobileNumber(createAgentRequest.getMobileNumber())
                .username(createAgentRequest.getUsername())
                .firstName(createAgentRequest.getFirstName())
                .lastName(createAgentRequest.getLastName())
                .merchantCode(merchantCode)
                .agent(parentAgent)
                .commissionGroupId(createAgentRequest.getCommissionGroupId())
                .build();
        return create(request);
    }

    private CommissionGroup resolveCommissionGroup(CreateUserRequest request, Merchant merchant) {
        if (MERCHANT_DEFINED == merchant.getCommissionStructure()) {
            return merchant.getCommissionGroup();
        }

        if (request.getAgent() != null) {
            return request.getAgent().getCommissionGroup();
        }

        if (ObjectUtils.isEmpty(request.getCommissionGroupId())) {
            throw new ValidationException("Commission group id is required");
        }

        return commissionGroupRepository.findById(request.getCommissionGroupId()).orElseThrow();
    }

    @Transactional
    public CreateUserResponse create(CreateUserRequest createUserRequest) {
        logger.info("Creating user {}", createUserRequest);

        validateRequest(createUserRequest);

        Optional<User> userByUsername = userRepository.findByUsername(createUserRequest.getUsername());
        if (userByUsername.isPresent()) {
            throw new DuplicateUserByUsernameException(createUserRequest.getUsername());
        }

        Merchant merchant = merchantRepository.findByMerchantCode(createUserRequest.getMerchantCode())
                .orElseThrow(() -> new RuntimeException("Merchant not found"));

        CommissionGroup commissionGroup = resolveCommissionGroup(createUserRequest, merchant);

        String generatedPassword = generatePassword(createUserRequest);
        String externalSystemId = keycloakService.addUser(createUserRequest, generatedPassword);

        User user = new User();
        user.setExternalSystemId(externalSystemId);
        user.setMerchant(merchant);
        user.setTemporaryPassword(true);
        user.setUsername(createUserRequest.getUsername());
        user.setAgent(createUserRequest.getAgent());
        user.setCommissionGroup(commissionGroup);

        User savedUser = userRepository.save(user);

        UserDTO userDTO = new UserDTO();
        userDTO.setMerchant(merchantMapper.fromMerchant(merchant));
        userDTO.setEmail(createUserRequest.getEmail());
        userDTO.setFirstName(createUserRequest.getFirstName());
        userDTO.setId(savedUser.getId());
        userDTO.setIdNumber(Utils.trimSpecialCharacters(createUserRequest.getIdNumber()).toUpperCase());
        userDTO.setLastName(createUserRequest.getLastName());
        userDTO.setMobileNumber(MsisdnUtil.formatMsisdnInternational(createUserRequest.getMobileNumber()));
        userDTO.setGroups(createUserRequest.getGroups());
        userDTO.setUsername(createUserRequest.getUsername());
        userDTO.setTemporaryPassword(true);
        userDTO.setAgentId(createUserRequest.getAgent() == null ? null : createUserRequest.getAgent().getId());
        userDTO.setCommissionGroup(CommissionGroupDto.fromCommissionGroup(commissionGroup));

        CreateUserResponse createUserResponse = new CreateUserResponse(userDTO);
        notifyUser(userDTO, generatedPassword);
        logger.info("Create user response for request {} is {}", createUserRequest, createUserResponse);
        return createUserResponse;
    }

    private void notifyUser(UserDTO user, String generatedPassword) {
        String message = String.format(PASSWORD_SMS_TEMPLATE, user.getFirstName(), user.getUsername(), generatedPassword);
        notificationService.sendSms(MsisdnUtil.formatMsisdnInternational(user.getMobileNumber()), message);
    }

    private void validateRequest(CreateUserRequest createUserRequest) {

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

    private String generatePassword(CreateUserRequest user) {

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
