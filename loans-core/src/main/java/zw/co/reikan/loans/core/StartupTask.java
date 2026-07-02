package zw.co.reikan.loans.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import zw.co.reikan.loans.core.api.CreateMerchantRequest;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.merchant.MerchantService;

import java.math.BigDecimal;
import java.util.Optional;

import static zw.co.reikan.loans.core.loan.DisbursementType.CUSTOMER_MOBILE_WALLET;
import static zw.co.reikan.loans.core.merchant.Merchant.DEFAULT_MERCHANT_CODE;
import static zw.co.reikan.loans.core.merchant.Merchant.DEFAULT_MERCHANT_NAME;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupTask implements CommandLineRunner {

    public static final String FAVORING_BULK_IT = "100-Favouring-BulkIT";
    public static final String ZERO_BASED_DEFAULT = "Default-BulkIT";
    private final MerchantService merchantService;
    private final CommissionGroupRepository commissionGroupRepository;

    @Override
    public void run(String... args) throws Exception {
        createDefaultMerchantGroups();
        createDefaultMerchant();
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

        createCommissionGroup(CommissionGroup.builder()
                .percentage(true)
                .providerCommission(new BigDecimal("100.0"))
                .agentCommission(new BigDecimal("0.0"))
                .name(ZERO_BASED_DEFAULT)
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
            log.warn(e.getMessage());
        }
    }
}
