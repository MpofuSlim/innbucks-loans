package zw.co.reikan.loans.core.merchant;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import zw.co.reikan.loans.core.api.CreateMerchantRequest;
import zw.co.reikan.loans.core.api.MerchantDto;
import zw.co.reikan.loans.core.api.UpdateMerchantRequest;
import zw.co.reikan.loans.core.audit.AuditLog;
import zw.co.reikan.loans.core.audit.AuditService;
import zw.co.reikan.loans.core.commission.CommissionGroup;
import zw.co.reikan.loans.core.commission.CommissionGroupRepository;
import zw.co.reikan.loans.core.exception.ValidationException;
import zw.co.reikan.loans.core.loan.DisbursementType;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static zw.co.reikan.loans.core.commission.CommissionStructure.AGENT_DEFINED;
import static zw.co.reikan.loans.core.commission.CommissionStructure.MERCHANT_DEFINED;

@Service
@RequiredArgsConstructor
public class MerchantService {

    public static final String PAYEE_CHANGED_EVENT = "MERCHANT_PAYEE_CHANGED";

    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;
    private final CommissionGroupRepository commissionGroupRepository;
    private final AuditService auditService;

    /**
     * @param maskAccountNumbers {@code true} for anyone but the admin who sets the
     *        account: it is where every approved loan of that merchant is paid.
     */
    public FindMerchantsResponse findMerchants(boolean maskAccountNumbers) {
        List<MerchantDto> merchantDtos = merchantMapper.fromMerchants(merchantRepository.findAll());
        if (maskAccountNumbers) {
            merchantDtos.forEach(m -> m.setAccountNumber(maskAccountNumber(m.getAccountNumber())));
        }
        return new FindMerchantsResponse(merchantDtos);
    }

    /** Last four characters only; a value that short is hidden entirely. */
    public static String maskAccountNumber(String accountNumber) {
        if (!StringUtils.hasLength(accountNumber)) {
            return accountNumber;
        }
        return accountNumber.length() <= 4 ? "****" : "****" + accountNumber.substring(accountNumber.length() - 4);
    }

    @Transactional
    public MerchantDto createMerchant(CreateMerchantRequest request, String actorId) {
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
        auditPayeeChange(savedMerchant, actorId, null, null);
        return merchantMapper.fromMerchant(savedMerchant);
    }

    /**
     * Update a merchant's editable details, identified by its immutable code.
     * The code, commission structure and commission group are intentionally not
     * touched — they are fixed at creation.
     */
    @Transactional
    public MerchantDto updateMerchant(String code, UpdateMerchantRequest request, String actorId) {
        Merchant merchant = merchantRepository.findByMerchantCode(code)
                .orElseThrow(() -> new ValidationException("Merchant with merchant code " + code + " not found"));
        validateUpdateRequest(request);
        DisbursementType oldDisbursementType = merchant.getDisbursementType();
        String oldAccountNumber = merchant.getAccountNumber();

        merchant.setCompanyName(request.getCompanyName());
        merchant.setPhysicalAddress(request.getPhysicalAddress());
        merchant.setContactPersonName(request.getContactPersonName());
        merchant.setContactPersonMobileNumber(request.getContactPersonMobileNumber());
        merchant.setContactPersonEmail(request.getContactPersonEmail());
        merchant.setAccountNumber(request.getAccountNumber());
        merchant.setDisbursementType(request.getDisbursementType());

        Merchant savedMerchant = merchantRepository.save(merchant);
        if (oldDisbursementType != savedMerchant.getDisbursementType()
                || !Objects.equals(oldAccountNumber, savedMerchant.getAccountNumber())) {
            auditPayeeChange(savedMerchant, actorId, oldDisbursementType, oldAccountNumber);
        }
        return merchantMapper.fromMerchant(savedMerchant);
    }

    /**
     * The disbursement type and account decide where approved loans are paid —
     * booking reads them live from the merchant — so every change must say who
     * made it. Account numbers are masked: the trail proves a change without
     * becoming another copy of the payee.
     */
    private void auditPayeeChange(Merchant merchant, String actorId,
                                  DisbursementType oldDisbursementType, String oldAccountNumber) {
        auditService.record(AuditLog.builder()
                .eventType(PAYEE_CHANGED_EVENT)
                .entityType("MERCHANT").entityId(String.valueOf(merchant.getId()))
                .actorId(actorId)
                .stateTransitionDelta("{\"from\":" + payeeJson(oldDisbursementType, oldAccountNumber)
                        + ",\"to\":" + payeeJson(merchant.getDisbursementType(), merchant.getAccountNumber()) + "}")
                .detail("merchantCode=" + merchant.getMerchantCode()
                        + " disbursementType " + oldDisbursementType + " -> " + merchant.getDisbursementType()
                        + ", accountNumber " + maskAccountNumber(oldAccountNumber)
                        + " -> " + maskAccountNumber(merchant.getAccountNumber())));
    }

    private static String payeeJson(DisbursementType disbursementType, String accountNumber) {
        return "{\"disbursementType\":" + jsonString(disbursementType == null ? null : disbursementType.name())
                + ",\"accountNumber\":" + jsonString(maskAccountNumber(accountNumber)) + "}";
    }

    /** The masked tail is still free text from the request, so escape it. */
    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder json = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                json.append('\\').append(c);
            } else if (c < 0x20) {
                json.append(String.format("\\u%04x", (int) c));
            } else {
                json.append(c);
            }
        }
        return json.append('"').toString();
    }

    private void validateUpdateRequest(UpdateMerchantRequest request) {
        if (!StringUtils.hasText(request.getCompanyName())) {
            throw new ValidationException("Name is required");
        }
        if (request.getDisbursementType() == null) {
            throw new ValidationException("Disbursement type is required");
        }
        if (request.getDisbursementType() == DisbursementType.MERCHANT_MOBILE_WALLET
                && !StringUtils.hasText(request.getAccountNumber())) {
            throw new ValidationException("Account number is required");
        }
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
