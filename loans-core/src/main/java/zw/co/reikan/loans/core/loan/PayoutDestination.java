package zw.co.reikan.loans.core.loan;

import zw.co.reikan.loans.core.merchant.Merchant;

import java.util.Objects;

/**
 * Where a loan's money is paid: the customer's own wallet, or a merchant's settlement account for a
 * consumer-finance loan. Every payout path reads it from here, so the booking, a recovery payout and
 * the customer's SMS can never disagree about it.
 *
 * <p>It is frozen onto the loan when credit approves it. The merchant row is editable afterwards, and
 * a loan must pay where credit signed off, not wherever that row points by the time the loan is
 * booked. A loan approved before the freeze existed has none recorded, and falls back to the
 * merchant's live settings, as every loan did before.</p>
 *
 * @param type            how the money is paid; null only for a legacy loan whose merchant has none
 * @param merchantAccount the merchant settlement account for {@code MERCHANT_MOBILE_WALLET}, else null
 * @param frozen          whether this was recorded at credit approval rather than read live
 */
public record PayoutDestination(DisbursementType type, String merchantAccount, boolean frozen) {

    public static PayoutDestination of(Loan loan) {
        if (loan.getApprovedDisbursementType() != null) {
            return new PayoutDestination(loan.getApprovedDisbursementType(), loan.getApprovedSettlementAccount(), true);
        }
        return live(loan.getMerchant());
    }

    /** What the merchant row says now: what an approval freezes, and what a legacy loan still pays. */
    public static PayoutDestination live(Merchant merchant) {
        DisbursementType type = merchant == null ? null : merchant.getDisbursementType();
        return new PayoutDestination(type,
                type == DisbursementType.MERCHANT_MOBILE_WALLET ? merchant.getAccountNumber() : null, false);
    }

    public boolean paysMerchant() {
        return type == DisbursementType.MERCHANT_MOBILE_WALLET;
    }

    /** Whether the merchant row would now pay somewhere other than this frozen destination. */
    public boolean differsFrom(Merchant merchant) {
        PayoutDestination now = live(merchant);
        return type != now.type() || !Objects.equals(merchantAccount, now.merchantAccount());
    }
}
