package zw.co.reikan.loans.core.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    final UserRepository userRepository;
    final PasswordEncoder passwordEncoder;
    final PasswordPolicyService passwordPolicyService;


    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> findActiveUserByUsername(String username) {
        return userRepository.findByUsernameIgnoringCaseAndEnabledTrue(username);
    }

    public Optional<User> findUserByUsername(String username) {
        return userRepository.findByUsernameIgnoringCase(username);
    }

    @Override
    public String create(UserRequest request) {

        final Optional<User> optional = userRepository.findByUsernameIgnoringCaseAndEnabledTrue(request.getUsername());

        if (optional.isPresent()) {
            throw new RuntimeException("User already exists");
        }

        final String encode = passwordEncoder.encode("$030vFHi0ZnLZf@YMQTDyAsZd");
        log.info("Encoded password: {}", encode);

        final User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .mobileNumber(request.getMobileNumber())
                .enabled(true)
                .role(request.getRole())
                .build();

        final User savedUser = userRepository.save(user);

        return String.valueOf(savedUser.getId());
    }

    public void recoverPassword(String email) {
        try {
            findUserByUsername(email)
                    .ifPresent(user -> {
                        final String rawPassword = passwordPolicyService.generatePassword();
                        user.setPassword(passwordEncoder.encode(rawPassword));
                        userRepository.save(user);
                    });
            log.info("Details saved successfully!");
        } catch (Exception ex) {
            log.error("", ex);
        }
    }

    @Override
    public List<User> findByAllUsers() {
        return userRepository.findAll();
    }

    @Override
    @Transactional
    public User save(User user) {
        AtomicReference<User> savedUser = new AtomicReference<>();
        userRepository.findById(user.getId())
                .ifPresent(u -> {
                    u.setEnabled(user.isEnabled());
                    u.setMobileNumber(user.getMobileNumber());
                    u.setRole(user.getRole());
                    savedUser.set(userRepository.save(u));
                });
        return savedUser.get();
    }

}