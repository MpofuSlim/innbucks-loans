package zw.co.reikan.loans.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import zw.co.reikan.loans.core.api.CreateMerchantRequest;
import zw.co.reikan.loans.core.merchant.MerchantService;

import static zw.co.reikan.loans.core.loan.DisbursementType.CUSTOMER_MOBILE_WALLET;
import static zw.co.reikan.loans.core.merchant.Merchant.DEFAULT_MERCHANT_CODE;
import static zw.co.reikan.loans.core.merchant.Merchant.DEFAULT_MERCHANT_NAME;

@Component
@RequiredArgsConstructor
@Slf4j
public class StartupTask implements CommandLineRunner {

    private final MerchantService merchantService;

    @Override
    public void run(String... args) throws Exception {
        log.info("Attempting to create Innbucks default merchant service");
        try {
            merchantService.createMerchant(CreateMerchantRequest.builder()
                    .code(DEFAULT_MERCHANT_CODE)
                    .name(DEFAULT_MERCHANT_NAME)
                    .disbursementType(CUSTOMER_MOBILE_WALLET)
                    .accountNumber(null)
                    .build());
        } catch (Exception e) {
            log.warn(e.getMessage(), e);
        }
    }
}
