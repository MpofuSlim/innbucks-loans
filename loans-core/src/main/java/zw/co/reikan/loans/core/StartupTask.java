package zw.co.reikan.loans.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import zw.co.reikan.loans.core.api.CreateMerchantRequest;
import zw.co.reikan.loans.core.api.CreateUserRequest;
import zw.co.reikan.loans.core.channel.Channel;
import zw.co.reikan.loans.core.channel.ChannelRepository;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.merchant.MerchantService;
import zw.co.reikan.loans.core.user.CreateUserService;
import zw.co.reikan.loans.core.user.FindUserService;
import zw.co.reikan.loans.core.user.User;
import zw.co.reikan.loans.core.user.UserGroup;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static zw.co.reikan.loans.core.loan.DisbursementType.CUSTOMER_MOBILE_WALLET;
import static zw.co.reikan.loans.core.merchant.Merchant.DEFAULT_MERCHANT_CODE;
import static zw.co.reikan.loans.core.merchant.Merchant.DEFAULT_MERCHANT_NAME;
import static zw.co.reikan.loans.core.user.User.SYSTEM_USER_NAME;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupTask implements CommandLineRunner {

    public static final String FAVORING_BULK_IT = "100-Favouring-BulkIT";
    private final MerchantService merchantService;
    private final CommissionGroupRepository commissionGroupRepository;
    private final ChannelRepository channelRepository;
    private final FindUserService findUserService;
    private final CreateUserService createUserService;

    @Override
    public void run(String... args) throws Exception {
        createDefaultMerchantGroups();
        createDefaultMerchant();
        createDefaultChannel();
    }

    private void createDefaultChannel() {
        log.info("Creating default system user");
        User systemUser = findUserService.findUserByUsername(SYSTEM_USER_NAME)
                .orElseGet(() -> {
                    createUserService.create(CreateUserRequest.builder()
                            .mobileNumber("0772819815")
                            .idNumber(DEFAULT_MERCHANT_CODE)
                            .username(SYSTEM_USER_NAME)
                            .lastName("USER")
                            .groups(List.of(UserGroup.BULKIT_ADMIN))
                            .build());
                    return findUserService.findUserByUsername(SYSTEM_USER_NAME).orElseThrow();
                });
        createChannel(Channel.builder()
                .systemUser(systemUser)
                .channelId(Channel.MOBILE_APP_CHANNEL)
                .name("Mobile App")
                .build());

        createChannel(Channel.builder()
                .systemUser(systemUser)
                .channelId(Channel.ADMIN_PORTAL_CHANNEL)
                .name("Admin Portal")
                .build());

        createChannel(Channel.builder()
                .systemUser(systemUser)
                .channelId(Channel.WEB_APP_CHANNEL)
                .name("Self Service Portal")
                .build());

    }

    private void createChannel(Channel channel) {
        log.info("Attempting to create channel: {}", channel);
        Optional<Channel> channelByChannelId = channelRepository.findChannelByChannelId(channel.getChannelId());
        if (channelByChannelId.isPresent()) {
            log.warn("Channel already exists: {}", channel.getChannelId());
            return;
        }
        channelRepository.save(channel);
    }

    private void createDefaultMerchantGroups() {

        createCommissionGroup(CommissionGroup.builder()
                .percentage(true)
                .providerCommission(new BigDecimal("80.0"))
                .agentCommission(new BigDecimal("20.0"))
                .name("80-20-Favouring-BulkIT")
                .build());

        createCommissionGroup(CommissionGroup.builder()
                .percentage(true)
                .providerCommission(new BigDecimal("100.0"))
                .agentCommission(new BigDecimal("0.0"))
                .name(FAVORING_BULK_IT)
                .build());
    }


    private void createCommissionGroup(CommissionGroup commissionGroup) {
        log.info("Attempting to create CommissionGroup: {}", commissionGroup);
        Optional<CommissionGroup> optionalCommissionGroup = commissionGroupRepository.findByNameIgnoreCase(commissionGroup.getName());
        if (optionalCommissionGroup.isPresent()) {
            log.warn("CommissionGroup already exists: {}", commissionGroup.getName());
            return;
        }
        commissionGroupRepository.save(commissionGroup);
    }

    private void createDefaultMerchant() {
        log.info("Attempting to create Innbucks default merchant service");
        try {

            commissionGroupRepository.findByNameIgnoreCase(FAVORING_BULK_IT)
                    .orElseThrow(() -> new ValidationException("Default commission group not found"));

            merchantService.createMerchant(CreateMerchantRequest.builder()
                    .code(DEFAULT_MERCHANT_CODE)
                    .companyName(DEFAULT_MERCHANT_NAME)
                    .disbursementType(CUSTOMER_MOBILE_WALLET)
                    .accountNumber(null)
                    .build());
        } catch (Exception e) {
            log.warn(e.getMessage(), e);
        }
    }
}
