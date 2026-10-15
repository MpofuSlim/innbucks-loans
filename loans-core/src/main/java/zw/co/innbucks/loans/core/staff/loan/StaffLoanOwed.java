package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherRedemption;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionSource;
import zw.co.innbucks.loans.core.voucher.VoucherRefund;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.core.voucher.VoucherStopReason;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a Staff Grocery Loan has to be collected on its payday, as its GetMore voucher stood at the payday's cut-off
 * (00:00 market time): the loan's whole repayment when the debt stands whatever was spent, or what was spent when the
 * loan is reduced to that (the treatment it was accepted under). Pure: no clock, no database.
 *
 * <p>An amount is <em>final</em> when nothing that can still happen changes it. It is provisional when the voucher was
 * still open at the cut-off and the loan is reduced to what is spent (more can be spent after), when the bank or the
 * store stopped the voucher and the debt stands (whether the full amount is owed for a voucher taken away is the
 * owner's decision, Q10), and whatever the treatment when loans' copy may be behind GetMore's: last read before the
 * cut-off, stopped by a sync issue, or with a sale held at the till. Under the reduced treatment a loan with interest
 * is provisional too: what is spent is not what is owed then. The amount is never below zero or above the loan's
 * whole repayment.</p>
 *
 * @param amountToCollect what to collect from salary on the payday
 * @param finalAmount     whether it is final
 * @param openBalance     what was still spendable on the voucher at the cut-off; zero once it was closed
 * @param couldRiseTo     the most a provisional amount could still become; null when final or unknown
 * @param flags           why it is what it is, and why it is not final, in their declared order
 */
public record StaffLoanOwed(BigDecimal amountToCollect, boolean finalAmount, BigDecimal openBalance,
                            BigDecimal couldRiseTo, Set<Flag> flags) {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

    /** What a payday line says about its loan and voucher. */
    public enum Flag {
        /** Accepted but its voucher was not issued by the cut-off: nothing was lent, nothing is collected. */
        VOUCHER_NOT_ISSUED,
        /** Paid out with no GetMore voucher on record: the full repayment, provisional unless the debt stands. */
        VOUCHER_UNKNOWN,
        /** Its voucher was cancelled before the cut-off with nothing spent: nothing is collected. */
        VOUCHER_CANCELLED,
        /** The bank or GetMore stopped its voucher before its own expiry (BANK_STOPPED, STORE_WITHDRAWN). */
        VOUCHER_STOPPED,
        /** Its voucher could still be spent at the cut-off. */
        VOUCHER_OPEN,
        /** Not read from GetMore since the cut-off: a sale made before it may not be recorded yet. */
        STALE,
        /** Its last sync stopped at a blocking issue: what follows that issue is not recorded. */
        SYNC_BLOCKED,
        /** A sale was ordered at GetMore and not yet invoiced at its last sync: not counted as spent yet. */
        HELD_SALES,
        /** Reduced to what was spent, but it carries interest or a fee: what was spent is not what is owed. */
        INTEREST_BEARING;

        /** Whether it leaves a line provisional whatever the treatment. */
        boolean provisionalAlways() {
            return this == STALE || this == SYNC_BLOCKED || this == HELD_SALES || this == INTEREST_BEARING;
        }
    }

    public StaffLoanOwed {
        EnumSet<Flag> ordered = EnumSet.noneOf(Flag.class);
        ordered.addAll(flags);
        flags = Collections.unmodifiableSet(ordered);
    }

    /**
     * A GetMore voucher as it stood at a payday's cut-off: spent and refunded as of then (GetMore's own times), its
     * state then, and what the last sync saw of held sales, blocking issues and when it ran.
     *
     * @param state         CANCELLED when cancelled before the cut-off; REDEEMED when all of it was spent by then;
     *                      EXPIRED when it had lapsed or been stopped by then; otherwise PARTIALLY_REDEEMED or ISSUED
     * @param stoppedReason why it was stopped early, when it had EXPIRED by the cut-off; null otherwise
     * @param spentNet      spent before the cut-off, less what was refunded before it
     * @param refunded      refunded before the cut-off
     * @param held          ordered at GetMore and not invoiced, at the last sync
     * @param stale         last read from GetMore before the cut-off, or never
     */
    public record VoucherAtCutoff(Long voucherId, VoucherStatus state, VoucherStopReason stoppedReason,
                                  String currency, BigDecimal faceValue, BigDecimal spentNet, BigDecimal refunded,
                                  BigDecimal held, LocalDateTime lastSyncedAt, VoucherSyncIssueKind syncBlockedBy,
                                  boolean stale) {

        /**
         * The voucher at {@code cutoff}, from its GetMore sale and refund rows (others are ignored).
         *
         * @param settledBy when set, only rows a finalised payday up to and including it had paid for: the voucher as
         *                  it was known when that payday was finalised. Null for every row recorded so far.
         */
        public static VoucherAtCutoff of(Voucher voucher, Collection<VoucherRedemption> spends,
                                         Collection<VoucherRefund> refunds, LocalDateTime cutoff,
                                         LocalDate settledBy) {
            BigDecimal spent = spends.stream()
                    .filter(spend -> voucher.getId().equals(spend.getVoucherId())
                            && spend.getSource() == VoucherRedemptionSource.GETMORE_SYNC
                            && spend.getRedeemedAt().isBefore(cutoff) && known(spend.getSettlementCycle(), settledBy))
                    .map(VoucherRedemption::getAmount).reduce(ZERO, BigDecimal::add);
            BigDecimal refunded = refunds.stream()
                    .filter(refund -> voucher.getId().equals(refund.getVoucherId())
                            && refund.getRefundedAt().isBefore(cutoff)
                            && known(refund.getSettlementCycle(), settledBy))
                    .map(VoucherRefund::getAmount).reduce(ZERO, BigDecimal::add);
            BigDecimal net = spent.subtract(refunded);
            VoucherStatus state;
            if (voucher.getCancelledAt() != null && voucher.getCancelledAt().isBefore(cutoff)) {
                state = VoucherStatus.CANCELLED;
            } else if (net.compareTo(voucher.getFaceValue()) == 0) {
                state = VoucherStatus.REDEEMED;
            } else if (!voucher.getExpiresAt().isAfter(cutoff)) {
                state = VoucherStatus.EXPIRED;
            } else if (net.signum() > 0) {
                state = VoucherStatus.PARTIALLY_REDEEMED;
            } else {
                state = VoucherStatus.ISSUED;
            }
            LocalDateTime synced = voucher.getSyncedAt();
            return new VoucherAtCutoff(voucher.getId(), state,
                    state == VoucherStatus.EXPIRED ? voucher.getStoppedReason() : null, voucher.getCurrency(),
                    voucher.getFaceValue(), net, refunded, voucher.getHeldAmount() == null ? ZERO
                    : voucher.getHeldAmount(), synced, voucher.getSyncBlockedBy(),
                    synced == null || synced.isBefore(cutoff));
        }

        private static boolean known(LocalDate settlementCycle, LocalDate settledBy) {
            return settledBy == null || (settlementCycle != null && !settlementCycle.isAfter(settledBy));
        }

        /** Whether it could still be spent at the cut-off. */
        public boolean open() {
            return state.isOpen();
        }
    }

    /**
     * What {@code loan} owes on its payday given its voucher at the cut-off.
     *
     * @param voucher null when the loan has no GetMore voucher
     * @throws IllegalArgumentException the loan is neither awaiting its voucher nor paid out (a written-off loan is
     *                                  recovered outside payroll; a repaid or cancelled one owes nothing)
     */
    public static StaffLoanOwed at(StaffLoan loan, VoucherAtCutoff voucher) {
        boolean paidOut = switch (loan.getStatus()) {
            case AWAITING_DISBURSEMENT -> false;
            case DISBURSED -> true;
            default -> throw new IllegalArgumentException("A " + loan.getStatus() + " Staff Grocery Loan ("
                    + loan.getReference() + ") is not collected on payday");
        };
        return of(paidOut, loan.getUnredeemedVoucherTreatment(), loan.getTotalRepayable(), loan.getAmount(),
                voucher);
    }

    /**
     * What a loan owes, paid out or not, under {@code treatment}, for a repayment of {@code totalRepayable} on
     * {@code amount} borrowed.
     */
    static StaffLoanOwed of(boolean paidOut, UnredeemedVoucherTreatment treatment, BigDecimal totalRepayable,
                            BigDecimal amount, VoucherAtCutoff voucher) {
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        if (!paidOut) {
            flags.add(Flag.VOUCHER_NOT_ISSUED);
            return checked(ZERO, true, ZERO, null, flags, totalRepayable);
        }
        boolean reduced = treatment == UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT;
        if (reduced && totalRepayable.compareTo(amount) != 0) {
            flags.add(Flag.INTEREST_BEARING);
        }
        if (voucher == null) {
            flags.add(Flag.VOUCHER_UNKNOWN);
            return checked(totalRepayable, !reduced, ZERO, null, flags, totalRepayable);
        }
        if (voucher.stale()) {
            flags.add(Flag.STALE);
        }
        if (voucher.syncBlockedBy() != null) {
            flags.add(Flag.SYNC_BLOCKED);
        }
        if (voucher.held().signum() > 0) {
            flags.add(Flag.HELD_SALES);
        }
        BigDecimal net = voucher.spentNet();
        return switch (voucher.state()) {
            case CANCELLED -> {
                flags.add(Flag.VOUCHER_CANCELLED);
                yield checked(ZERO, true, ZERO, null, flags, totalRepayable);
            }
            case ISSUED, PARTIALLY_REDEEMED -> {
                flags.add(Flag.VOUCHER_OPEN);
                BigDecimal open = voucher.faceValue().subtract(net);
                yield reduced ? checked(net, false, open, voucher.faceValue(), flags, totalRepayable)
                        : checked(totalRepayable, true, open, null, flags, totalRepayable);
            }
            case EXPIRED -> {
                if (voucher.stoppedReason() == VoucherStopReason.BANK_STOPPED
                        || voucher.stoppedReason() == VoucherStopReason.STORE_WITHDRAWN) {
                    flags.add(Flag.VOUCHER_STOPPED);
                    yield reduced ? checked(net, true, ZERO, null, flags, totalRepayable)
                            : checked(net, false, ZERO, totalRepayable, flags, totalRepayable);
                }
                yield checked(reduced ? net : totalRepayable, true, ZERO, null, flags, totalRepayable);
            }
            case REDEEMED -> checked(reduced ? net : totalRepayable, true, ZERO, null, flags, totalRepayable);
        };
    }

    /** Provisional when any flag says so; never below zero or above the whole repayment. */
    private static StaffLoanOwed checked(BigDecimal amount, boolean finalByTreatment, BigDecimal openBalance,
                                         BigDecimal couldRiseTo, Set<Flag> flags, BigDecimal totalRepayable) {
        if (amount.signum() < 0 || amount.compareTo(totalRepayable) > 0) {
            throw new IllegalStateException("A payday amount of " + amount.toPlainString() + " is outside 0 to "
                    + totalRepayable.toPlainString());
        }
        boolean finalAmount = finalByTreatment && flags.stream().noneMatch(Flag::provisionalAlways);
        return new StaffLoanOwed(amount.setScale(2, RoundingMode.UNNECESSARY), finalAmount,
                openBalance.setScale(2, RoundingMode.UNNECESSARY), finalAmount ? null : couldRiseTo, flags);
    }
}
