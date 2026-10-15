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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * cut-off, stopped by a sync issue, with a sale held at the till, or its coupon deleted by the store at a time loans
 * cannot date (no sync saw it gone until after the cut-off). Under the reduced treatment a loan with interest
 * is provisional too: what is spent is not what is owed then. The amount is never below zero or above the loan's
 * whole repayment: {@link #at} refuses one that would be ({@link OutOfRange}), and the statement shows such a loan as
 * {@link #inconsistent} instead.</p>
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
        /**
         * The bank or GetMore stopped its voucher before its own expiry (BANK_STOPPED, STORE_WITHDRAWN): never final
         * under DEBT_STANDS. Or GetMore deleted its coupon at a time loans cannot date (no sync saw it gone until after
         * the cut-off): never final under either treatment.
         */
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
        INTEREST_BEARING,
        /**
         * Its voucher's GetMore rows at the cut-off come to an amount below zero or above the whole repayment: the
         * amount shown is held within those, and the rows need checking before payroll.
         */
        ROWS_INCONSISTENT;

        /** Whether it leaves a line provisional whatever the treatment. */
        boolean provisionalAlways() {
            return this == STALE || this == SYNC_BLOCKED || this == HELD_SALES || this == INTEREST_BEARING
                    || this == ROWS_INCONSISTENT;
        }
    }

    /** What a voucher's rows at the cut-off came to is outside 0 to the loan's whole repayment. */
    public static final class OutOfRange extends IllegalStateException {

        OutOfRange(BigDecimal amount, BigDecimal totalRepayable) {
            super("A payday amount of " + amount.toPlainString() + " is outside 0 to "
                    + totalRepayable.toPlainString());
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
     * <p>Refunds count the way the sync recorded them ({@code GetMoreSpendPlan}): order by order, in time order (a
     * spend before a refund at the same moment), a refund counting whole once its order's spends cover it. A refund
     * GetMore dated before its order's next invoiced level waited for that level, and that level is dated by the
     * order's last update, which can fall after the cut-off; such a refund counts from that level's time, not its
     * own, as the rows' {@code balance_after} has it. So what was spent less refunded is never below zero.</p>
     *
     * @param state         CANCELLED when cancelled before the cut-off; REDEEMED when all of it was spent by then;
     *                      EXPIRED when it had lapsed or been stopped by then (a single-use coupon from its one use,
     *                      when that was before the cut-off, however late a sync noticed it); otherwise
     *                      PARTIALLY_REDEEMED or ISSUED
     * @param stoppedReason why it was stopped early, when it had EXPIRED by the cut-off; STORE_WITHDRAWN too on a
     *                      voucher still open then by its own dates, when GetMore's deletion was noticed only after
     *                      the cut-off and cannot be dated ({@link #stopUndated}); null otherwise
     * @param spentNet      spent before the cut-off, less what was refunded before it
     * @param refunded      refunded before the cut-off, each refund from when its order's spends covered it
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
            List<VoucherRedemption> sales = spends.stream()
                    .filter(spend -> voucher.getId().equals(spend.getVoucherId())
                            && spend.getSource() == VoucherRedemptionSource.GETMORE_SYNC
                            && known(spend.getSettlementCycle(), settledBy))
                    .toList();
            Map<Long, List<Event>> byOrder = new HashMap<>();
            sales.forEach(spend -> byOrder.computeIfAbsent(spend.getExternalOrderId(), order -> new ArrayList<>())
                    .add(new Event(spend.getRedeemedAt(), false, spend.getId(), spend.getAmount())));
            refunds.stream()
                    .filter(refund -> voucher.getId().equals(refund.getVoucherId())
                            && known(refund.getSettlementCycle(), settledBy))
                    .forEach(refund -> byOrder.computeIfAbsent(refund.getExternalOrderId(), order -> new ArrayList<>())
                            .add(new Event(refund.getRefundedAt(), true, refund.getId(), refund.getAmount())));
            BigDecimal spent = ZERO;
            BigDecimal refunded = ZERO;
            for (List<Event> events : byOrder.values()) {
                BigDecimal[] order = orderAt(events, cutoff);
                spent = spent.add(order[0]);
                refunded = refunded.add(order[1]);
            }
            BigDecimal net = spent.subtract(refunded);
            VoucherStopReason stopped = voucher.getStoppedReason();
            VoucherStatus state;
            if (voucher.getCancelledAt() != null && voucher.getCancelledAt().isBefore(cutoff)) {
                state = VoucherStatus.CANCELLED;
            } else if (net.compareTo(voucher.getFaceValue()) >= 0) {
                state = VoucherStatus.REDEEMED;
            } else if (!voucher.getExpiresAt().isAfter(cutoff) || usedUpBefore(voucher, sales, cutoff)) {
                state = VoucherStatus.EXPIRED;
            } else if (net.signum() > 0) {
                state = VoucherStatus.PARTIALLY_REDEEMED;
            } else {
                state = VoucherStatus.ISSUED;
            }
            // GetMore's deletion of a coupon is dated by the sync that noticed it: noticed after the cut-off, it may
            // have come before it, so the stop is kept on a voucher still open by its own dates (stopUndated()).
            boolean undated = state.isOpen() && stopped == VoucherStopReason.STORE_WITHDRAWN;
            LocalDateTime synced = voucher.getSyncedAt();
            return new VoucherAtCutoff(voucher.getId(), state,
                    state == VoucherStatus.EXPIRED || undated ? stopped : null, voucher.getCurrency(),
                    voucher.getFaceValue(), net, refunded, voucher.getHeldAmount() == null ? ZERO
                    : voucher.getHeldAmount(), synced, voucher.getSyncBlockedBy(),
                    synced == null || synced.isBefore(cutoff));
        }

        /**
         * Whether a single-use coupon stopped as used up ({@code SINGLE_USE_EXHAUSTED}) was used before the cut-off. Its
         * stop is dated by the sync that noticed the use, which can be days after it (nothing syncs on a timer): the
         * coupon was used up at its one use, the first of its GetMore sales. A use only held at the till (no sale yet)
         * dates nothing: the voucher reads as open, and the held sale keeps the line provisional.
         */
        private static boolean usedUpBefore(Voucher voucher, List<VoucherRedemption> sales, LocalDateTime cutoff) {
            return voucher.getStoppedReason() == VoucherStopReason.SINGLE_USE_EXHAUSTED && sales.stream()
                    .map(VoucherRedemption::getRedeemedAt).filter(Objects::nonNull)
                    .anyMatch(used -> used.isBefore(cutoff));
        }

        /** A sale or a refund of one order, as the replay below needs it. */
        private record Event(LocalDateTime at, boolean refund, Long id, BigDecimal amount) {
        }

        private static final Comparator<Event> IN_TIME = Comparator.comparing(Event::at)
                .thenComparing(Event::refund)
                .thenComparing(Event::id, Comparator.nullsLast(Comparator.naturalOrder()));

        /**
         * One order's spent and refunded before {@code cutoff}: its rows in time order, a refund counted whole once the
         * order's spends cover it, and one waiting for a spend counted when that spend comes, if before the cut-off.
         */
        private static BigDecimal[] orderAt(List<Event> events, LocalDateTime cutoff) {
            events.sort(IN_TIME);
            BigDecimal spent = ZERO;
            BigDecimal refunded = ZERO;
            List<Event> waiting = new ArrayList<>();
            for (Event event : events) {
                if (!event.at().isBefore(cutoff)) {
                    break;
                }
                if (event.refund()) {
                    if (spent.compareTo(refunded.add(event.amount())) >= 0) {
                        refunded = refunded.add(event.amount());
                    } else {
                        waiting.add(event);
                    }
                    continue;
                }
                spent = spent.add(event.amount());
                for (Iterator<Event> refund = waiting.iterator(); refund.hasNext(); ) {
                    BigDecimal amount = refund.next().amount();
                    if (spent.compareTo(refunded.add(amount)) >= 0) {
                        refunded = refunded.add(amount);
                        refund.remove();
                    }
                }
            }
            return new BigDecimal[]{spent, refunded};
        }

        private static boolean known(LocalDate settlementCycle, LocalDate settledBy) {
            return settledBy == null || (settlementCycle != null && !settlementCycle.isAfter(settledBy));
        }

        /**
         * Whether GetMore deleted its coupon at a time loans cannot date: noticed by a sync after the cut-off, so it
         * may have been gone before it. Neither open nor closed at the cut-off, as far as loans knows.
         */
        public boolean stopUndated() {
            return state.isOpen() && stoppedReason != null;
        }

        /** Whether it could still be spent at the cut-off, as far as loans knows. */
        public boolean open() {
            return state.isOpen() && !stopUndated();
        }
    }

    /**
     * What {@code loan} owes on its payday given its voucher at the cut-off.
     *
     * @param voucher null when the loan has no GetMore voucher
     * @throws IllegalArgumentException the loan is neither awaiting its voucher nor paid out (a written-off loan is
     *                                  recovered outside payroll; a repaid or cancelled one owes nothing)
     * @throws OutOfRange               what the voucher's rows come to is below zero or above the whole repayment
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
                if (voucher.stopUndated()) {
                    // GetMore deleted the coupon, and no sync saw it until after the cut-off: it may have been taken
                    // away before payday, so what was spent is never collected as final (Q10), nor the whole
                    // repayment.
                    flags.add(Flag.VOUCHER_STOPPED);
                    yield checked(net, false, ZERO, reduced ? voucher.faceValue() : totalRepayable, flags,
                            totalRepayable);
                }
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

    /**
     * The line of a paid-out loan whose voucher's rows at the cut-off come to an amount outside 0 to
     * {@code totalRepayable}, which {@link #at} refuses: what was spent less refunded, held within that range,
     * provisional and flagged {@link Flag#ROWS_INCONSISTENT}, so one voucher cannot take a whole statement down. It
     * could rise to the whole repayment.
     */
    static StaffLoanOwed inconsistent(BigDecimal totalRepayable, VoucherAtCutoff voucher) {
        Objects.requireNonNull(voucher, "voucher");
        Set<Flag> flags = EnumSet.of(Flag.ROWS_INCONSISTENT);
        if (voucher.stale()) {
            flags.add(Flag.STALE);
        }
        if (voucher.syncBlockedBy() != null) {
            flags.add(Flag.SYNC_BLOCKED);
        }
        if (voucher.held().signum() > 0) {
            flags.add(Flag.HELD_SALES);
        }
        BigDecimal open = voucher.open() ? within(voucher.faceValue().subtract(voucher.spentNet()),
                voucher.faceValue()) : ZERO;
        return checked(within(voucher.spentNet(), totalRepayable), false, open, totalRepayable, flags,
                totalRepayable);
    }

    /** {@code amount} held within 0 and {@code most}. */
    private static BigDecimal within(BigDecimal amount, BigDecimal most) {
        return amount.signum() < 0 ? ZERO : amount.min(most);
    }

    /** Provisional when any flag says so; never below zero or above the whole repayment. */
    private static StaffLoanOwed checked(BigDecimal amount, boolean finalByTreatment, BigDecimal openBalance,
                                         BigDecimal couldRiseTo, Set<Flag> flags, BigDecimal totalRepayable) {
        if (amount.signum() < 0 || amount.compareTo(totalRepayable) > 0) {
            throw new OutOfRange(amount, totalRepayable);
        }
        boolean finalAmount = finalByTreatment && flags.stream().noneMatch(Flag::provisionalAlways);
        return new StaffLoanOwed(amount.setScale(2, RoundingMode.UNNECESSARY), finalAmount,
                openBalance.setScale(2, RoundingMode.UNNECESSARY), finalAmount ? null : couldRiseTo, flags);
    }
}
