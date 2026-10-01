package zw.co.innbucks.loans.core.voucher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.auth.AuthService;
import zw.co.innbucks.loans.core.config.MarketTimeZone;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * GetMore's side of a voucher (FR-SGL-036): checking one at the till, and spending it.
 *
 * <p>A redemption locks the voucher's row until it commits, so two tills presenting the same code are served one after
 * the other and can never spend the same balance twice. GetMore's transaction reference makes a retry safe: the same
 * reference for the same voucher and amount returns the first answer and spends nothing more; the same reference for
 * anything else is refused. A code is never logged; the masked form is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherRedemptionService {

    private final VoucherRepository voucherRepository;
    private final VoucherRedemptionRepository redemptionRepository;
    private final VoucherCodeVault vault;
    private final VoucherProperties properties;
    private final AuthService authService;
    private final AuditService auditService;
    private final MarketTimeZone marketTimeZone;

    /**
     * What a till is told about a voucher before the sale. Any voucher that exists is answered, spendable or not
     * ({@code redeemable} says which); only a code that is not a voucher code, or that no voucher has, is refused.
     */
    @Transactional(readOnly = true)
    public VoucherValidationResponse validate(VoucherValidationRequest request) {
        vault.requireConfigured();
        String username = authService.getLoggedInUsername();
        String code = codeOf(request.getCode(), username, request.getOutletId());
        Voucher voucher = voucherRepository.findByCodeHmac(vault.fingerprint(code))
                .orElseThrow(() -> unknown(code, username, request.getOutletId()));
        VoucherValidationResponse answer = VoucherValidationResponse.of(voucher, marketTimeZone.nowUtc(),
                properties.isPartialRedemptionAllowed());
        log.info("Voucher {} ({}) checked by {} at outlet {}: {}, {} {} left", voucher.getId(), voucher.maskedCode(),
                username, request.getOutletId(), answer.status(), answer.balance(), answer.currency());
        return answer;
    }

    /**
     * Spends {@code amount} of a voucher at a till.
     *
     * @throws VoucherRefusedException the code, the voucher's state or the amount does not allow it; nothing was spent
     */
    @Transactional
    public VoucherRedemptionResult redeem(VoucherRedemptionRequest request) {
        vault.requireConfigured();
        String username = authService.getLoggedInUsername();
        String outletId = request.getOutletId().strip();
        String reference = request.getReference().strip();
        String code = codeOf(request.getCode(), username, outletId);
        Voucher voucher = voucherRepository.lockByCodeHmac(vault.fingerprint(code))
                .orElseThrow(() -> unknown(code, username, outletId));
        BigDecimal amount = request.getAmount();
        VoucherRedemption earlier = redemptionRepository.findByMerchantReference(reference).orElse(null);
        if (earlier != null) {
            if (earlier.getVoucherId().equals(voucher.getId()) && earlier.getAmount().compareTo(amount) == 0) {
                log.info("Redemption {} of voucher {} sent again by {}; answered as before, nothing more spent",
                        reference, voucher.getId(), username);
                return VoucherRedemptionResult.of(earlier, voucher, true);
            }
            throw refused(VoucherRefusal.REFERENCE_REUSED, voucher, username, outletId);
        }
        LocalDateTime now = marketTimeZone.nowUtc();
        switch (voucher.statusAt(now)) {
            case CANCELLED -> throw refused(VoucherRefusal.VOUCHER_CANCELLED, voucher, username, outletId);
            case REDEEMED -> throw refused(VoucherRefusal.VOUCHER_REDEEMED, voucher, username, outletId);
            case EXPIRED -> throw refused(VoucherRefusal.VOUCHER_EXPIRED, voucher, username, outletId);
            default -> {
                // open: the amount decides
            }
        }
        if (!voucher.getCurrency().equals(request.getCurrency())) {
            throw refused(VoucherRefusal.CURRENCY_MISMATCH, voucher, username, outletId);
        }
        BigDecimal balance = voucher.balance();
        if (amount.compareTo(balance) > 0) {
            throw new VoucherRefusedException(VoucherRefusal.INSUFFICIENT_BALANCE, String.format(
                    "The amount is more than the %s %s left on this voucher", voucher.getCurrency(),
                    balance.toPlainString()));
        }
        if (!properties.isPartialRedemptionAllowed() && amount.compareTo(balance) != 0) {
            throw refused(VoucherRefusal.PARTIAL_REDEMPTION_NOT_ALLOWED, voucher, username, outletId);
        }
        String outletName = StringUtils.trimToNull(request.getOutletName());
        // Validated to at most two decimals, so this never rounds.
        BigDecimal spent = amount.setScale(2, RoundingMode.UNNECESSARY);
        voucher.redeem(spent, outletName == null ? outletId : outletName, now);
        voucherRepository.save(voucher);
        VoucherRedemption redemption;
        try {
            redemption = redemptionRepository.saveAndFlush(VoucherRedemption.builder()
                    .voucherId(voucher.getId())
                    .merchantReference(reference)
                    .amount(spent)
                    .balanceAfter(voucher.balance())
                    .outletId(outletId)
                    .outletName(outletName)
                    .redeemedBy(username)
                    .redeemedAt(now)
                    .build());
        } catch (DataIntegrityViolationException race) {
            // Another voucher's redemption took the same reference at the same moment; this one is rolled back.
            throw refused(VoucherRefusal.REFERENCE_REUSED, voucher, username, outletId);
        }
        auditService.record(AuditLog.builder()
                .eventType("VOUCHER_REDEEMED")
                .entityType(VoucherService.ENTITY).entityId(String.valueOf(voucher.getId()))
                .actorId(username).channelUsed("getmore")
                .detail("reference:" + reference + ";amount:" + redemption.getAmount() + " " + voucher.getCurrency()
                        + ";balanceAfter:" + redemption.getBalanceAfter() + ";outlet:" + outletId));
        log.info("Voucher {} ({}) redeemed by {} at outlet {}: {} {}, {} left, reference {}", voucher.getId(),
                voucher.maskedCode(), username, outletId, redemption.getAmount(), voucher.getCurrency(),
                redemption.getBalanceAfter(), reference);
        return VoucherRedemptionResult.of(redemption, voucher, false);
    }

    /** The code as digits, or a refusal when it cannot be one; nothing is looked up for a malformed code. */
    private static String codeOf(String typed, String username, String outletId) {
        return VoucherCodes.normalize(typed).orElseThrow(() -> {
            log.warn("Malformed voucher code from {} at outlet {}", username, outletId);
            return new VoucherRefusedException(VoucherRefusal.INVALID_VOUCHER_CODE);
        });
    }

    private static VoucherRefusedException unknown(String code, String username, String outletId) {
        log.warn("Unknown voucher code (ending {}) from {} at outlet {}", VoucherCodes.lastFour(code), username,
                outletId);
        return new VoucherRefusedException(VoucherRefusal.VOUCHER_NOT_FOUND);
    }

    private static VoucherRefusedException refused(VoucherRefusal refusal, Voucher voucher, String username,
                                                   String outletId) {
        log.warn("Voucher {} ({}) refused to {} at outlet {}: {}", voucher.getId(), voucher.maskedCode(), username,
                outletId, refusal);
        return new VoucherRefusedException(refusal);
    }
}
