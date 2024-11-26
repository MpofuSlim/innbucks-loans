package zw.co.reikan.loans.core.merchant;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.api.CreateMerchantRequest;
import zw.co.reikan.loans.core.api.MerchantDto;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.loan.DisbursementType;

import java.util.List;
import java.util.UUID;

import static zw.co.reikan.loans.core.commission.CommissionStructure.AGENT_DEFINED;
import static zw.co.reikan.loans.core.commission.CommissionStructure.MERCHANT_DEFINED;

@Service
@RequiredArgsConstructor
public class MerchantService {
    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;
    private final CommissionGroupRepository commissionGroupRepository;

    public FindMerchantsResponse findMerchants() {
        List<MerchantDto> merchantDtos = merchantMapper.fromMerchants(merchantRepository.findAll());
        return new FindMerchantsResponse(merchantDtos);
    }

    @Transactional
    public MerchantDto createMerchant(CreateMerchantRequest request) {
        validateRequest(request);
        CommissionGroup commissionGroup = resolveCommissionGroup(request);
        Merchant merchant = Merchant.builder()
                .merchantCode(StringUtils.hasText(request.getCode()) ? request.getCode() : UUID.randomUUID().toString())
                .accountNumber(request.getAccountNumber())
                .disbursementType(request.getDisbursementType())
                .companyName(request.getCompanyName())
                .commissionGroup(commissionGroup)
                .commissionStructure(request.getCommissionStructure())
                .contactPersonName(request.getContactPersonName())
                .contactPersonEmail(request.getContactPersonEmail())
                .contactPersonMobileNumber(request.getContactPersonMobileNumber())
                .physicalAddress(request.getPhysicalAddress())
                .build();
        Merchant savedMerchant = merchantRepository.save(merchant);
        return merchantMapper.fromMerchant(savedMerchant);
    }

    private CommissionGroup resolveCommissionGroup(CreateMerchantRequest request) {
        if (AGENT_DEFINED == request.getCommissionStructure()) {
            return null;
        }
        if (MERCHANT_DEFINED == request.getCommissionStructure()
                && ObjectUtils.isEmpty(request.getCommissionGroupId())) {
            throw new ValidationException("Commission group id is required");
        }
        return commissionGroupRepository.findById(request.getCommissionGroupId()).orElseThrow();
    }

    private void validateRequest(CreateMerchantRequest request) {
        Boolean exists = merchantRepository.existsByMerchantCode(request.getCode());
        if (exists) {
            throw new ValidationException("Merchant with merchant code " + Merchant.DEFAULT_MERCHANT_CODE + " already exists");
        }
        if (!StringUtils.hasText(request.getCompanyName())) {
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
