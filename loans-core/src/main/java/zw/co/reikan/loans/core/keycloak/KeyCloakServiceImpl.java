package zw.co.reikan.loans.core.keycloak;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.AccessTokenResponse;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.api.*;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.merchant.MerchantMapper;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserGroup;
import zw.co.reikan.loans.core.user.UserRepository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static zw.co.reikan.loans.core.user.User.SYSTEM_USER_NAME;


@Service
@RequiredArgsConstructor
@Slf4j
public class KeyCloakServiceImpl implements KeycloakService {

    public static final String MERCHANT_CODE = "merchant_code";
    public static final String ID_NUMBER = "id_number";
    public static final String MOBILE_NUMBER = "mobile_number";

    private final Keycloak keycloak;
    private final UserRepository userRepository;
    private final MerchantRepository merchantRepository;
    private final AuthProperties authProperties;
    private final MerchantMapper merchantMapper;

    public AuthResponse login(AuthRequest request) {
        AccessTokenResponse tokenResponse = KeycloakBuilder.builder().serverUrl(authProperties.getAuthUrl())
                .realm(authProperties.getRealm()).clientId(authProperties.getClientId()).username(request.getUsername())
                .password(request.getPassword()).grantType(OAuth2Constants.PASSWORD).build().tokenManager()
                .getAccessToken();
        return getLoginResponse(tokenResponse, request.getUsername());
    }

    private AuthResponse getLoginResponse(AccessTokenResponse tokenResponse, String username) {
        AuthResponse loginResponse = new AuthResponse();
        loginResponse.setExpiresIn(tokenResponse.getExpiresIn());
        loginResponse.setAccessToken(tokenResponse.getToken());
        loginResponse.setTokenType(tokenResponse.getTokenType());
        Optional<User> userResult = userRepository.findByUsername(username);

        if (userResult.isEmpty()) {
            throw new InternalAuthenticationServiceException(String.format("%s not configured", username));
        }

        userResult.ifPresent(user -> {
            loginResponse.setTemporaryPassword(user.getTemporaryPassword());
            loginResponse.setMerchantName(user.getMerchant().getCompanyName());
            loginResponse.setMerchantCode(user.getMerchant().getMerchantCode());
            if (user.getAgent() != null) {
                loginResponse.setAgentId(user.getAgent().getId());
                loginResponse.setAgentName(user.getAgent().getUsername());
            }
        });

        return loginResponse;
    }

    @Override
    public User getLoggedInUser() {
        String loggedInUsername = getLoggedInUsername();
        return userRepository.findByUsername(loggedInUsername)
                .orElseThrow(() -> new ValidationException("User %s not found".formatted(loggedInUsername)));
    }

    public String getLoggedInUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() != null) {
            Jwt token = (Jwt) auth.getPrincipal();
            return token.getClaimAsString("preferred_username");
        }
        return SYSTEM_USER_NAME;
    }

    public void resetPassword(String newPassword, String userId, String username) {
        LocalDateTime auditDateTime = LocalDateTime.now();
        UsersResource userResource = keycloak.realm(authProperties.getRealm()).users();
        CredentialRepresentation passwordCred = new CredentialRepresentation();
        passwordCred.setTemporary(false);
        passwordCred.setType(CredentialRepresentation.PASSWORD);
        passwordCred.setValue(newPassword.trim());
        userResource.get(userId).resetPassword(passwordCred);
        Optional<User> userResult = userRepository.findByUsername(username);
        userResult.ifPresent(user -> {
            user.setTemporaryPassword(false);
            userRepository.save(user);
        });
    }

    public String addUser(CreateUserRequest user, String password) {
        UsersResource usersResource = keycloak.realm(authProperties.getRealm()).users();
        CredentialRepresentation credentialRepresentation = createPasswordCredentials(password);
        UserRepresentation kcUser = new UserRepresentation();
        kcUser.setUsername(user.getUsername());
        kcUser.setCredentials(Collections.singletonList(credentialRepresentation));
        kcUser.setFirstName(user.getFirstName());
        kcUser.setLastName(user.getLastName());
        kcUser.setEmail(user.getEmail());
        kcUser.setEnabled(true);
        kcUser.setEmailVerified(true);
        kcUser.setGroups(user.getGroups().stream().map(UserGroup::name).collect(Collectors.toList()));

        merchantRepository.findByMerchantCode(user.getMerchantCode())
                .orElseThrow(() -> new RuntimeException("Could not find merchant"));

        kcUser.singleAttribute(MOBILE_NUMBER, user.getMobileNumber());
        kcUser.singleAttribute(ID_NUMBER, user.getIdNumber());
        kcUser.singleAttribute(MERCHANT_CODE, String.valueOf(user.getMerchantCode()));
        return CreatedResponseUtil.getCreatedId(usersResource.create(kcUser));
    }

    private CredentialRepresentation createPasswordCredentials(String password) {
        CredentialRepresentation passwordCredentials = new CredentialRepresentation();
        passwordCredentials.setTemporary(false);
        passwordCredentials.setType(CredentialRepresentation.PASSWORD);
        passwordCredentials.setValue(password);
        return passwordCredentials;
    }

    public List<UserDTO> findUsersByMerchantCode(String merchantCode) {
        return keycloak.realm(authProperties.getRealm()).users()
                .searchByAttributes(MERCHANT_CODE + ":" + merchantCode).stream()
                .map(this::convertFromKeycloakUserToUserDTO).collect(Collectors.toList());
    }

    public List<UserDTO> search(SearchUserRequest searchUserRequest) {
        validateSearchRequest(searchUserRequest);
        return keycloak.realm(authProperties.getRealm()).users()
                .search(searchUserRequest.getSearchText(),
                        (searchUserRequest.getPageNumber() - 1) * searchUserRequest.getPageSize(),
                        searchUserRequest.getPageSize())
                .stream().map(this::convertFromKeycloakUserToUserDTO).collect(Collectors.toList());
    }

    private void validateSearchRequest(SearchUserRequest searchUserRequest) {
        if (searchUserRequest == null) {
            throw new ValidationException("User search criteria is required");
        }
        if (!StringUtils.hasText(searchUserRequest.getSearchText())) {
            throw new ValidationException("User search text required");
        }
        if (searchUserRequest.getPageNumber() == null) {
            throw new ValidationException("User search page number is required");
        }
        if (searchUserRequest.getPageSize() == null) {
            throw new ValidationException("User search page size is required");
        }
        if (searchUserRequest.getPageNumber() <= 0) {
            throw new ValidationException("User search page number cannot be negative or zero");
        }
        if (searchUserRequest.getPageSize() <= 0) {
            throw new ValidationException("User search page size cannot be negative or zero");
        }
    }

    private UserDTO convertFromKeycloakUserToUserDTO(UserRepresentation userRepresentation) {
        UserDTO userDTO = new UserDTO();
        Map<String, List<String>> userAttributes = userRepresentation.getAttributes();
        userDTO.setEmail(userRepresentation.getEmail());
        userDTO.setFirstName(userRepresentation.getFirstName());
        userDTO.setIdNumber(userAttributes.get(ID_NUMBER).get(0));
        userDTO.setLastName(userRepresentation.getLastName());
        userDTO.setMobileNumber(userAttributes.get(MOBILE_NUMBER).get(0));
        userDTO.setUsername(userRepresentation.getUsername());

        userRepository.findByExternalSystemId(userRepresentation.getId())
                .ifPresent(u -> userDTO.setAgentId(u.getAgent() == null ? null : u.getAgent().getId()));

        String merchantCode = userAttributes.get(MERCHANT_CODE).get(0);

        Optional<Merchant> merchantOptional = merchantRepository.findByMerchantCode(merchantCode);

        userDTO.setMerchant(merchantMapper.fromMerchant(merchantOptional.orElseThrow(() ->
                new RuntimeException("Could not find merchant"))));

        userDTO.setGroups(getGroupsForUser(userRepresentation.getId()).stream().map(UserGroup::valueOf)
                .collect(Collectors.toList()));

        return userDTO;
    }

    private List<String> getGroupsForUser(String userId) {
        return keycloak.realm(authProperties.getRealm()).users().get(userId).groups().stream()
                .map(GroupRepresentation::getName).collect(Collectors.toList());
    }

    @Override
    public void deleteUser(String userId) {
        try {
            log.info("Deleting user {}", userId);
            keycloak.realm(authProperties.getRealm()).users().delete(userId);
        } catch (Exception e) {
            log.error("Error deleting user {}", userId, e);
        }
    }

    public UserDTO getUser(String userId) {
        UserRepresentation representation = keycloak.realm(authProperties.getRealm()).users().get(userId).toRepresentation();
        return convertFromKeycloakUserToUserDTO(representation);
    }
}
