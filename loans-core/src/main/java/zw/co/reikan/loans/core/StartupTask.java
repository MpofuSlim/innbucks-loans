package zw.co.reikan.loans.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.api.CreateMerchantRequest;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.merchant.MerchantRepository;
import zw.co.reikan.loans.core.merchant.MerchantService;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserGroup;
import zw.co.reikan.loans.core.user.UserRepository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static zw.co.reikan.loans.core.commission.CommissionStructure.MERCHANT_DEFINED;
import static zw.co.reikan.loans.core.loan.DisbursementType.CUSTOMER_MOBILE_WALLET;
import static zw.co.reikan.loans.core.merchant.Merchant.DEFAULT_MERCHANT_CODE;
import static zw.co.reikan.loans.core.merchant.Merchant.DEFAULT_MERCHANT_NAME;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupTask implements CommandLineRunner {

    public static final String FAVORING_BULK_IT = "100-Favouring-BulkIT";
    public static final String ZERO_BASED_DEFAULT = "Default-BulkIT";

    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEV_FALLBACK_ADMIN_PASSWORD = "#Pass123";

    private final MerchantService merchantService;
    private final CommissionGroupRepository commissionGroupRepository;
    private final MerchantRepository merchantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        createDefaultMerchantGroups();
        createDefaultMerchant();
        createAdminUserIfAbsent();
    }

    private void createDefaultMerchantGroups() {

        createCommissionGroup(CommissionGroup.builder()
                .percentage(true)
                .providerCommission(new BigDecimal("80.0"))
                .agentCommission(new BigDecimal("20.0"))
                .name("80-20-Favouring-BulkIT")
                .enabled(true)
                .build());

        createCommissionGroup(CommissionGroup.builder()
                .percentage(true)
                .providerCommission(new BigDecimal("100.0"))
                .agentCommission(new BigDecimal("0.0"))
                .name(FAVORING_BULK_IT)
                .enabled(true)
                .build());

        createCommissionGroup(CommissionGroup.builder()
                .percentage(true)
                .providerCommission(new BigDecimal("100.0"))
                .agentCommission(new BigDecimal("0.0"))
                .name(ZERO_BASED_DEFAULT)
                .enabled(true)
                .build());
    }


    private void createCommissionGroup(CommissionGroup commissionGroup) {
        log.info("Attempting to create CommissionGroup: {}", commissionGroup);
        Optional<CommissionGroup> optionalCommissionGroup = commissionGroupRepository.findByNameIgnoreCase(commissionGroup.getName());
        if (optionalCommissionGroup.isPresent()) {
            // Heal DBs seeded before commission groups were created enabled: a
            // disabled default is invisible to GET /api/parameters/commission-groups.
            CommissionGroup existing = optionalCommissionGroup.get();
            if (!existing.isEnabled()) {
                existing.setEnabled(true);
                commissionGroupRepository.save(existing);
                log.info("Enabled existing CommissionGroup: {}", existing.getName());
            } else {
                log.warn("CommissionGroup already exists: {}", commissionGroup.getName());
            }
            return;
        }
        commissionGroupRepository.save(commissionGroup);
    }

    /**
     * Bootstraps a single super-admin so a fresh install is reachable. Idempotent:
     * the password is only ever set at creation and is never touched again once the
     * account exists. The password is taken from the {@code BULKIT_PASSWORD} env var;
     * when that is blank a development fallback of {@code #Pass123} is used so a local
     * boot works out of the box — set the env var in every deployed environment.
     */
    private void createAdminUserIfAbsent() {
        String username = Optional.ofNullable(System.getenv("BOOTSTRAP_ADMIN_USERNAME"))
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_ADMIN_USERNAME);

        if (userRepository.findByUsername(username).isPresent()) {
            log.info("Admin user '{}' already exists — leaving it untouched", username);
            return;
        }

        String password = Optional.ofNullable(System.getenv("BULKIT_PASSWORD"))
                .filter(StringUtils::hasText)
                .orElseGet(() -> {
                    log.warn("BULKIT_PASSWORD not set — bootstrapping admin '{}' with the development "
                            + "fallback password. Set BULKIT_PASSWORD in non-local environments.", username);
                    return DEV_FALLBACK_ADMIN_PASSWORD;
                });

        Optional<Merchant> merchant = merchantRepository.findByMerchantCode(DEFAULT_MERCHANT_CODE);
        if (merchant.isEmpty()) {
            log.warn("Default merchant not found — skipping admin bootstrap");
            return;
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setExternalSystemId(UUID.randomUUID().toString());
        admin.setFirstName("System");
        admin.setLastName("Administrator");
        admin.setMerchant(merchant.get());
        admin.setTemporaryPassword(true);
        admin.setGroups(Set.of(UserGroup.BULKIT_ADMIN));
        commissionGroupRepository.findByNameIgnoreCase(FAVORING_BULK_IT).ifPresent(admin::setCommissionGroup);

        userRepository.save(admin);
        log.info("Created bootstrap admin user '{}'", username);
    }

    private void createDefaultMerchant() {
        log.info("Attempting to create Innbucks default merchant service");
        try {
            if (merchantRepository.existsByMerchantCode(DEFAULT_MERCHANT_CODE)) {
                log.info("Default merchant already exists");
                return;
            }

            CommissionGroup defaultGroup = commissionGroupRepository.findByNameIgnoreCase(FAVORING_BULK_IT)
                    .orElseThrow(() -> new ValidationException("Default commission group not found"));

            merchantService.createMerchant(CreateMerchantRequest.builder()
                    .code(DEFAULT_MERCHANT_CODE)
                    .companyName(DEFAULT_MERCHANT_NAME)
                    .disbursementType(CUSTOMER_MOBILE_WALLET)
                    .commissionStructure(MERCHANT_DEFINED)
                    .commissionGroupId(defaultGroup.getId())
                    .accountNumber(null)
                    .build());
            log.info("Created default merchant");
        } catch (Exception e) {
            log.warn("Could not create default merchant", e);
        }
    }
}
