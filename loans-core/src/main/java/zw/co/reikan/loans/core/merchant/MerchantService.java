package zw.co.reikan.loans.core.merchant;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.api.CreateMerchantRequest;
import zw.co.reikan.loans.core.api.MerchantDto;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.loan.DisbursementType;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MerchantService {
    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;

    public FindMerchantsResponse findMerchants() {
        List<MerchantDto> merchantDtos = merchantMapper.fromMerchants(merchantRepository.findAll());
        return new FindMerchantsResponse(merchantDtos);
    }

    @Transactional
    public MerchantDto createMerchant(CreateMerchantRequest request) {
        validateRequest(request);
        Merchant merchant = Merchant.builder()
                .merchantCode(UUID.randomUUID().toString())
                .accountNumber(request.getAccountNumber())
                .disbursementType(request.getDisbursementType())
                .name(request.getName())
                .build();
        Merchant savedMerchant = merchantRepository.save(merchant);
        return merchantMapper.fromMerchant(savedMerchant);
    }

    private void validateRequest(CreateMerchantRequest request) {

        if (!StringUtils.hasText(request.getName())) {
            throw new ValidationException("Name is required");
        }
        if (request.getDisbursementType() == null) {
            throw new ValidationException("Disbursement type is required");
        }

        if (request.getDisbursementType() == DisbursementType.MERCHANT_MOBILE_WALLET &&
                !StringUtils.hasText(request.getAccountNumber())) {
            throw new ValidationException("Account number is required");
        }
    }
}
