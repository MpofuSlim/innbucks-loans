package zw.co.innbucks.loans.core.merchant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.loan.DisbursementType;

import java.util.Optional;

/**
 * Which merchant the Staff Grocery Loan is for: the one a new loan is accepted for and named in its agreement
 * (FR-SGL-026), paid to (FR-SGL-032), and whose tills alone can take its voucher (FR-SGL-035, FR-SGL-036). It is one of
 * loans' merchant records, and a SUPER_ADMIN changes which. A loan already accepted keeps the merchant it was accepted
 * for, and so does its voucher.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffLoanMerchantService {

    static final String CHANGED = "STAFF_LOAN_MERCHANT_CHANGED";

    private final MerchantRepository merchantRepository;
    private final AuditService auditService;

    /** The merchant a loan accepted now would be for; empty when none is set. */
    @Transactional(readOnly = true)
    public Optional<Merchant> current() {
        return merchantRepository.findByStaffLoanMerchantTrue();
    }

    /** @throws NotFoundException no merchant is set */
    @Transactional(readOnly = true)
    public StaffLoanMerchantResponse get() {
        return StaffLoanMerchantResponse.of(current().orElseThrow(() ->
                new NotFoundException("No merchant is set for the Staff Grocery Loan")));
    }

    /**
     * Makes {@code merchantCode} the Staff Grocery Loan's merchant from now on. Audited with the merchant it replaces.
     * Choosing the one already set changes nothing and records nothing.
     *
     * @throws NotFoundException   no merchant has this code
     * @throws ValidationException the merchant is paid to the customer's wallet, which a voucher loan never is
     * @throws ConflictException   another change landed at the same moment
     */
    @Transactional
    public StaffLoanMerchantResponse change(String merchantCode, String actorId) {
        String code = merchantCode.strip();
        Merchant merchant = merchantRepository.findByMerchantCode(code)
                .orElseThrow(() -> new NotFoundException("Merchant " + code + " not found"));
        if (merchant.getDisbursementType() != DisbursementType.MERCHANT_MOBILE_WALLET) {
            throw new ValidationException("Merchant " + code + " is not paid to its own account: a Staff Grocery Loan"
                    + " is paid to the merchant, never to the borrower, so set its disbursement type to "
                    + DisbursementType.MERCHANT_MOBILE_WALLET + " first");
        }
        Optional<Merchant> previous = merchantRepository.lockStaffLoanMerchant();
        if (previous.filter(held -> held.getId().equals(merchant.getId())).isPresent()) {
            return StaffLoanMerchantResponse.of(merchant);
        }
        try {
            previous.ifPresent(held -> {
                held.setStaffLoanMerchant(false);
                merchantRepository.saveAndFlush(held);
            });
            merchant.setStaffLoanMerchant(true);
            merchantRepository.saveAndFlush(merchant);
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException("The Staff Grocery Loan's merchant was changed at the same moment; read it"
                    + " again");
        }
        String from = previous.map(Merchant::getMerchantCode).orElse(null);
        auditService.record(AuditLog.builder()
                .eventType(CHANGED)
                .entityType("MERCHANT").entityId(String.valueOf(merchant.getId()))
                .actorId(actorId)
                .stateTransitionDelta("{\"from\":" + json(from) + ",\"to\":" + json(merchant.getMerchantCode()) + "}")
                .detail("staffLoanMerchant " + from + " -> " + merchant.getMerchantCode()));
        log.info("{} made merchant {} ({}) the Staff Grocery Loan's merchant, in place of {}", actorId,
                merchant.getMerchantCode(), merchant.getCompanyName(), from);
        return StaffLoanMerchantResponse.of(merchant);
    }

    /** A merchant code as JSON: codes are letters, digits and hyphens when made here, but one can be supplied. */
    private static String json(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
