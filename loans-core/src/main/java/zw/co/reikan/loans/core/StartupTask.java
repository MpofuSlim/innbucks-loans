package zw.co.reikan.loans.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.api.CreateMerchantRequest;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.config.DeploymentProfiles;
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
import java.util.stream.Stream;

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
    /** Published in this repo, so only ever used when a dev/test/local/it profile is active. */
    private static final String DEV_FALLBACK_ADMIN_PASSWORD = "#Pass123";

    private final MerchantService merchantService;
    private final CommissionGroupRepository commissionGroupRepository;
    private final MerchantRepository merchantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

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
     * account exists. The password is the {@code bootstrap.admin.password} property
     * (bound from the {@code BULKIT_PASSWORD} env var, or set in /app/config). When
     * it is blank, a deployment creates NO admin; only a dev/test/local/it profile
     * falls back to the development password so a local boot works out of the box.
     */
    private void createAdminUserIfAbsent() {
        String username = Optional.ofNullable(environment.getProperty("bootstrap.admin.username"))
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_ADMIN_USERNAME);

        if (userRepository.findByUsername(username).isPresent()) {
            log.info("Admin user '{}' already exists — leaving it untouched", username);
            return;
        }

        String password = resolveBootstrapPassword(username);
        if (password == null) {
            return;
        }

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

    /**
     * Read through the Environment, not System.getenv, so a mounted /app/config can
     * supply it. BULKIT_PASSWORD is also read directly because that config location
     * replaces the packaged application.yml, and with it the property mapping.
     * Returns null when no admin should be created.
     */
    private String resolveBootstrapPassword(String username) {
        Optional<String> configured = Stream.of("bootstrap.admin.password", "BULKIT_PASSWORD")
                .map(environment::getProperty)
                .filter(StringUtils::hasText)
                .findFirst();
        if (configured.isPresent()) {
            return configured.get();
        }
        if (DeploymentProfiles.isDeployment(environment)) {
            // Not fatal: environments that already have an admin never reach here,
            // and refusing to boot would not provision one either.
            log.error("Bootstrap admin '{}' NOT created: no password is configured and no dev/test/local/it "
                    + "profile is active. Set BULKIT_PASSWORD (env) or bootstrap.admin.password (/app/config) "
                    + "and restart; the account is created once, with a temporary password.", username);
            return null;
        }
        log.warn("BULKIT_PASSWORD not set — bootstrapping admin '{}' with the development fallback password "
                + "(dev/test/local/it profile active).", username);
        return DEV_FALLBACK_ADMIN_PASSWORD;
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
