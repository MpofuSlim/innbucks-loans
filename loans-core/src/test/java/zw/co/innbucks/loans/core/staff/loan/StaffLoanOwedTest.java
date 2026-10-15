package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanOwed.Flag;
import zw.co.innbucks.loans.core.staff.loan.StaffLoanOwed.VoucherAtCutoff;
import zw.co.innbucks.loans.core.voucher.Voucher;
import zw.co.innbucks.loans.core.voucher.VoucherCodeSource;
import zw.co.innbucks.loans.core.voucher.VoucherRedemption;
import zw.co.innbucks.loans.core.voucher.VoucherRedemptionSource;
import zw.co.innbucks.loans.core.voucher.VoucherRefund;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.core.voucher.VoucherStopReason;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a Staff Grocery Loan owes on its payday: both treatments against every state its voucher can be in at the
 * cut-off and every reason it was stopped, which amounts are final, the flags that make one provisional whatever the
 * treatment, and the voucher at the cut-off read from its rows. Rutendo Chari's SGL-2026-000164, USD 150.00, due on
 * 20 November 2026, whose cut-off is 00:00 that day in Harare (22:00 UTC on the 19th).
 */
class StaffLoanOwedTest {

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 11, 19, 22, 0);
    private static final BigDecimal FACE = new BigDecimal("150.00");

    private static StaffLoan loan(StaffLoanStatus status, UnredeemedVoucherTreatment treatment, String total) {
        return StaffLoan.builder().id(164L).reference("SGL-2026-000164").status(status)
                .unredeemedVoucherTreatment(treatment).amount(FACE).totalRepayable(new BigDecimal(total))
                .currency("USD").dueDate(LocalDate.of(2026, 11, 20)).build();
    }

    private static StaffLoan disbursed(UnredeemedVoucherTreatment treatment) {
        return loan(StaffLoanStatus.DISBURSED, treatment, "150.00");
    }

    /** Voucher 9 at the cut-off, read since it. */
    private static VoucherAtCutoff at(VoucherStatus state, VoucherStopReason stopped, String spentNet) {
        return new VoucherAtCutoff(9L, state, stopped, "USD", FACE, new BigDecimal(spentNet), new BigDecimal("0.00"),
                new BigDecimal("0.00"), CUTOFF.plusHours(10), null, false);
    }

    static Stream<Arguments> table() {
        UnredeemedVoucherTreatment debt = UnredeemedVoucherTreatment.DEBT_STANDS;
        UnredeemedVoucherTreatment reduced = UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT;
        List<Arguments> rows = new ArrayList<>();
        // state, stop reason, spent, treatment -> amount, final, couldRiseTo, openBalance, flags
        rows.add(Arguments.of(VoucherStatus.REDEEMED, null, "150.00", debt, "150.00", true, null, "0.00", Set.of()));
        rows.add(Arguments.of(VoucherStatus.REDEEMED, null, "150.00", reduced, "150.00", true, null, "0.00",
                Set.of()));
        for (VoucherStopReason lapse : new VoucherStopReason[]{null, VoucherStopReason.SINGLE_USE_EXHAUSTED,
                VoucherStopReason.SIMULATOR_EXPIRED}) {
            rows.add(Arguments.of(VoucherStatus.EXPIRED, lapse, "90.00", debt, "150.00", true, null, "0.00",
                    Set.of()));
            rows.add(Arguments.of(VoucherStatus.EXPIRED, lapse, "90.00", reduced, "90.00", true, null, "0.00",
                    Set.of()));
        }
        rows.add(Arguments.of(VoucherStatus.PARTIALLY_REDEEMED, null, "45.50", debt, "150.00", true, null, "104.50",
                Set.of(Flag.VOUCHER_OPEN)));
        rows.add(Arguments.of(VoucherStatus.PARTIALLY_REDEEMED, null, "45.50", reduced, "45.50", false, "150.00",
                "104.50", Set.of(Flag.VOUCHER_OPEN)));
        rows.add(Arguments.of(VoucherStatus.ISSUED, null, "0.00", debt, "150.00", true, null, "150.00",
                Set.of(Flag.VOUCHER_OPEN)));
        rows.add(Arguments.of(VoucherStatus.ISSUED, null, "0.00", reduced, "0.00", false, "150.00", "150.00",
                Set.of(Flag.VOUCHER_OPEN)));
        for (VoucherStopReason stop : new VoucherStopReason[]{VoucherStopReason.BANK_STOPPED,
                VoucherStopReason.STORE_WITHDRAWN}) {
            rows.add(Arguments.of(VoucherStatus.EXPIRED, stop, "20.00", debt, "20.00", false, "150.00", "0.00",
                    Set.of(Flag.VOUCHER_STOPPED)));
            rows.add(Arguments.of(VoucherStatus.EXPIRED, stop, "20.00", reduced, "20.00", true, null, "0.00",
                    Set.of(Flag.VOUCHER_STOPPED)));
        }
        rows.add(Arguments.of(VoucherStatus.CANCELLED, null, "0.00", debt, "0.00", true, null, "0.00",
                Set.of(Flag.VOUCHER_CANCELLED)));
        rows.add(Arguments.of(VoucherStatus.CANCELLED, null, "0.00", reduced, "0.00", true, null, "0.00",
                Set.of(Flag.VOUCHER_CANCELLED)));
        return rows.stream();
    }

    @ParameterizedTest(name = "{0} {1}, {3}: {4} final={5}")
    @MethodSource("table")
    @DisplayName("each treatment against each voucher state and stop reason at the cut-off")
    void treatments(VoucherStatus state, VoucherStopReason stopped, String spent,
                    UnredeemedVoucherTreatment treatment, String amount, boolean finalAmount, String couldRiseTo,
                    String openBalance, Set<Flag> flags) {
        StaffLoanOwed owed = StaffLoanOwed.at(disbursed(treatment), at(state, stopped, spent));

        assertThat(owed.amountToCollect()).isEqualByComparingTo(amount);
        assertThat(owed.amountToCollect().scale()).isEqualTo(2);
        assertThat(owed.finalAmount()).isEqualTo(finalAmount);
        assertThat(owed.couldRiseTo()).isEqualTo(couldRiseTo == null ? null : new BigDecimal(couldRiseTo));
        assertThat(owed.openBalance()).isEqualByComparingTo(openBalance);
        assertThat(owed.flags()).containsExactlyInAnyOrderElementsOf(flags);
        assertThat(owed.amountToCollect()).isBetween(BigDecimal.ZERO, new BigDecimal("150.00"));
    }

    @ParameterizedTest
    @EnumSource(UnredeemedVoucherTreatment.class)
    @DisplayName("a loan whose voucher was not issued by the cut-off owes nothing, finally")
    void awaiting(UnredeemedVoucherTreatment treatment) {
        StaffLoanOwed owed = StaffLoanOwed.at(loan(StaffLoanStatus.AWAITING_DISBURSEMENT, treatment, "150.00"),
                null);

        assertThat(owed.amountToCollect()).isEqualByComparingTo("0.00");
        assertThat(owed.finalAmount()).isTrue();
        assertThat(owed.flags()).containsExactly(Flag.VOUCHER_NOT_ISSUED);
    }

    @Test
    @DisplayName("paid out with no GetMore voucher on record: the full repayment, provisional unless the debt stands")
    void noVoucher() {
        StaffLoanOwed debt = StaffLoanOwed.at(disbursed(UnredeemedVoucherTreatment.DEBT_STANDS), null);
        StaffLoanOwed reduced = StaffLoanOwed.at(disbursed(UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT),
                null);

        assertThat(debt.amountToCollect()).isEqualByComparingTo("150.00");
        assertThat(debt.finalAmount()).isTrue();
        assertThat(debt.flags()).containsExactly(Flag.VOUCHER_UNKNOWN);
        assertThat(reduced.amountToCollect()).isEqualByComparingTo("150.00");
        assertThat(reduced.finalAmount()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(UnredeemedVoucherTreatment.class)
    @DisplayName("STALE, SYNC_BLOCKED and HELD_SALES each leave even a final amount provisional")
    void provisionalWhateverTheTreatment(UnredeemedVoucherTreatment treatment) {
        VoucherAtCutoff stale = new VoucherAtCutoff(9L, VoucherStatus.REDEEMED, null, "USD", FACE, FACE,
                new BigDecimal("0.00"), new BigDecimal("0.00"), CUTOFF.minusDays(2), null, true);
        VoucherAtCutoff blocked = new VoucherAtCutoff(9L, VoucherStatus.REDEEMED, null, "USD", FACE, FACE,
                new BigDecimal("0.00"), new BigDecimal("0.00"), CUTOFF.plusHours(1), VoucherSyncIssueKind.OVERSPENT,
                false);
        VoucherAtCutoff held = new VoucherAtCutoff(9L, VoucherStatus.EXPIRED, null, "USD", FACE,
                new BigDecimal("100.00"), new BigDecimal("0.00"), new BigDecimal("20.00"), CUTOFF.plusHours(1), null,
                false);

        assertThat(StaffLoanOwed.at(disbursed(treatment), stale)).satisfies(owed -> {
            assertThat(owed.finalAmount()).isFalse();
            assertThat(owed.flags()).containsExactly(Flag.STALE);
            assertThat(owed.amountToCollect()).isEqualByComparingTo("150.00");
        });
        assertThat(StaffLoanOwed.at(disbursed(treatment), blocked)).satisfies(owed -> {
            assertThat(owed.finalAmount()).isFalse();
            assertThat(owed.flags()).containsExactly(Flag.SYNC_BLOCKED);
        });
        assertThat(StaffLoanOwed.at(disbursed(treatment), held)).satisfies(owed -> {
            assertThat(owed.finalAmount()).isFalse();
            assertThat(owed.flags()).containsExactly(Flag.HELD_SALES);
        });
    }

    @Test
    @DisplayName("reduced to what was spent, a loan with interest is provisional: spent is not owed")
    void interestBearing() {
        StaffLoanOwed owed = StaffLoanOwed.at(loan(StaffLoanStatus.DISBURSED,
                UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT, "160.00"), at(VoucherStatus.EXPIRED, null, "90.00"));
        StaffLoanOwed debt = StaffLoanOwed.at(loan(StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.DEBT_STANDS,
                "160.00"), at(VoucherStatus.EXPIRED, null, "90.00"));

        assertThat(owed.flags()).containsExactly(Flag.INTEREST_BEARING);
        assertThat(owed.finalAmount()).isFalse();
        assertThat(owed.amountToCollect()).isEqualByComparingTo("90.00");
        assertThat(debt.flags()).isEmpty();
        assertThat(debt.amountToCollect()).isEqualByComparingTo("160.00");
        assertThat(debt.finalAmount()).isTrue();
    }

    @Test
    @DisplayName("never above the whole repayment, and only awaiting or paid-out loans are collected")
    void invariantAndStatuses() {
        StaffLoan underRepaid = loan(StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT,
                "100.00");
        assertThatThrownBy(() -> StaffLoanOwed.at(underRepaid, at(VoucherStatus.REDEEMED, null, "150.00")))
                .isInstanceOf(StaffLoanOwed.OutOfRange.class).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside 0 to 100.00");

        for (StaffLoanStatus status : new StaffLoanStatus[]{StaffLoanStatus.WRITTEN_OFF, StaffLoanStatus.REPAID,
                StaffLoanStatus.CANCELLED}) {
            assertThatThrownBy(() -> StaffLoanOwed.at(loan(status, UnredeemedVoucherTreatment.DEBT_STANDS,
                    "150.00"), null)).as(status.name()).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("is not collected on payday");
        }
    }

    // ---- the voucher at the cut-off ----------------------------------------------------------------------------------

    private static Voucher.VoucherBuilder voucher() {
        return Voucher.builder().id(9L).faceValue(FACE).redeemedAmount(new BigDecimal("90.00")).currency("USD")
                .issuedAt(LocalDateTime.of(2026, 10, 14, 8, 20, 7))
                .expiresAt(LocalDateTime.of(2026, 11, 13, 21, 59, 59, 999_999_000)).status(VoucherStatus.EXPIRED)
                .codeSource(VoucherCodeSource.GETMORE).storeMode(GetMoreMode.SIMULATED).staffLoanId(164L)
                .heldAmount(new BigDecimal("0.00")).syncedAt(CUTOFF.plusHours(10));
    }

    private static VoucherRedemption spend(long id, String amount, LocalDateTime at, LocalDate settled) {
        return spend(id, 12L, amount, at, settled);
    }

    private static VoucherRedemption spend(long id, long order, String amount, LocalDateTime at, LocalDate settled) {
        return VoucherRedemption.builder().id(id).voucherId(9L).amount(new BigDecimal(amount))
                .source(VoucherRedemptionSource.GETMORE_SYNC).storeMode(GetMoreMode.SIMULATED).redeemedAt(at)
                .externalOrderId(order).recordedAt(at.plusMinutes(1)).settlementCycle(settled).build();
    }

    private static VoucherRefund refund(long id, String amount, LocalDateTime at, LocalDate settled) {
        return refund(id, 12L, amount, at, settled);
    }

    private static VoucherRefund refund(long id, long order, String amount, LocalDateTime at, LocalDate settled) {
        return VoucherRefund.builder().id(id).voucherId(9L).amount(new BigDecimal(amount))
                .storeMode(GetMoreMode.SIMULATED).externalOrderId(order).refundedAt(at).recordedAt(at.plusMinutes(1))
                .settlementCycle(settled).build();
    }

    @Test
    @DisplayName("rows count until the cut-off (23:59:59 on the 19th in Harare), not from it; till rows never")
    void rowsBeforeTheCutoff() {
        List<VoucherRedemption> spends = List.of(spend(1, "45.50", CUTOFF.minusSeconds(1), null),
                spend(2, "54.50", CUTOFF, null),
                VoucherRedemption.builder().id(3L).voucherId(9L).amount(new BigDecimal("5.00"))
                        .source(VoucherRedemptionSource.TILL).redeemedAt(CUTOFF.minusDays(1)).build(),
                VoucherRedemption.builder().id(4L).voucherId(10L).amount(new BigDecimal("7.00"))
                        .source(VoucherRedemptionSource.GETMORE_SYNC).redeemedAt(CUTOFF.minusDays(1)).build());
        List<VoucherRefund> refunds = List.of(refund(1, "10.00", CUTOFF.minusSeconds(1), null),
                refund(2, "4.00", CUTOFF, null));

        VoucherAtCutoff at = VoucherAtCutoff.of(voucher().build(), spends, refunds, CUTOFF, null);

        assertThat(at.spentNet()).isEqualByComparingTo("35.50");
        assertThat(at.refunded()).isEqualByComparingTo("10.00");
        assertThat(at.state()).as("its natural expiry, on the 13th").isEqualTo(VoucherStatus.EXPIRED);
        assertThat(at.stoppedReason()).isNull();
        assertThat(at.stale()).isFalse();
    }

    @Test
    @DisplayName("the state then, not now: open before its expiry, stopped only once stopped, cancelled before only")
    void stateAtTheCutoff() {
        LocalDateTime later = CUTOFF.plusDays(5);
        Voucher open = voucher().expiresAt(later).status(VoucherStatus.EXPIRED)
                .stoppedReason(VoucherStopReason.BANK_STOPPED).build();
        assertThat(VoucherAtCutoff.of(open, List.of(spend(1, "45.50", CUTOFF.minusDays(3), null)), List.of(), CUTOFF,
                null)).satisfies(at -> {
            assertThat(at.state()).isEqualTo(VoucherStatus.PARTIALLY_REDEEMED);
            assertThat(at.stoppedReason()).as("stopped after the cut-off: not stopped then").isNull();
        });
        assertThat(VoucherAtCutoff.of(voucher().expiresAt(later).build(), List.of(), List.of(), CUTOFF, null).state())
                .isEqualTo(VoucherStatus.ISSUED);
        assertThat(VoucherAtCutoff.of(voucher().expiresAt(CUTOFF).stoppedReason(VoucherStopReason.STORE_WITHDRAWN)
                .build(), List.of(), List.of(), CUTOFF, null)).satisfies(at -> {
            assertThat(at.state()).as("expiring at the cut-off itself").isEqualTo(VoucherStatus.EXPIRED);
            assertThat(at.stoppedReason()).isEqualTo(VoucherStopReason.STORE_WITHDRAWN);
        });
        assertThat(VoucherAtCutoff.of(voucher().build(), List.of(spend(1, "150.00", CUTOFF.minusDays(9), null)),
                List.of(), CUTOFF, null).state()).as("spent out before it lapsed").isEqualTo(VoucherStatus.REDEEMED);
        assertThat(VoucherAtCutoff.of(voucher().status(VoucherStatus.CANCELLED).cancelledAt(CUTOFF.minusDays(30))
                .build(), List.of(), List.of(), CUTOFF, null).state()).isEqualTo(VoucherStatus.CANCELLED);
        assertThat(VoucherAtCutoff.of(voucher().expiresAt(later).status(VoucherStatus.CANCELLED)
                .cancelledAt(CUTOFF.plusDays(1)).build(), List.of(), List.of(), CUTOFF, null).state())
                .as("cancelled after the cut-off").isEqualTo(VoucherStatus.ISSUED);
    }

    @Test
    @DisplayName("stale when last read before the cut-off or never; held is as last synced")
    void staleAndHeld() {
        assertThat(VoucherAtCutoff.of(voucher().syncedAt(CUTOFF.minusNanos(1000)).build(), List.of(), List.of(),
                CUTOFF, null).stale()).isTrue();
        assertThat(VoucherAtCutoff.of(voucher().syncedAt(null).build(), List.of(), List.of(), CUTOFF, null).stale())
                .isTrue();
        assertThat(VoucherAtCutoff.of(voucher().syncedAt(CUTOFF).build(), List.of(), List.of(), CUTOFF, null).stale())
                .isFalse();
        assertThat(VoucherAtCutoff.of(voucher().heldAmount(new BigDecimal("20.00"))
                .syncBlockedBy(VoucherSyncIssueKind.OVERSPENT).build(), List.of(), List.of(), CUTOFF, null))
                .satisfies(at -> {
                    assertThat(at.held()).isEqualByComparingTo("20.00");
                    assertThat(at.syncBlockedBy()).isEqualTo(VoucherSyncIssueKind.OVERSPENT);
                });
    }

    @Test
    @DisplayName("as known at a finalisation: only rows a payday up to it had paid for")
    void asSettledBy() {
        LocalDate payday = LocalDate.of(2026, 11, 20);
        List<VoucherRedemption> spends = List.of(spend(1, "45.50", CUTOFF.minusDays(30), LocalDate.of(2026, 10, 20)),
                spend(2, "54.50", CUTOFF.minusDays(10), payday),
                spend(3, "20.00", CUTOFF.minusDays(2), null),
                spend(4, "5.00", CUTOFF.minusDays(1), LocalDate.of(2026, 12, 20)));
        List<VoucherRefund> refunds = List.of(refund(1, "10.00", CUTOFF.minusDays(5), payday),
                refund(2, "3.00", CUTOFF.minusDays(1), null));

        VoucherAtCutoff known = VoucherAtCutoff.of(voucher().build(), spends, refunds, CUTOFF, payday);
        VoucherAtCutoff now = VoucherAtCutoff.of(voucher().build(), spends, refunds, CUTOFF, null);

        assertThat(known.spentNet()).isEqualByComparingTo("90.00");
        assertThat(known.refunded()).isEqualByComparingTo("10.00");
        assertThat(now.spentNet()).isEqualByComparingTo("112.00");
        assertThat(now.refunded()).isEqualByComparingTo("13.00");
    }

    @Test
    @DisplayName("a refund dated before the level it waited for counts from that level: never below zero")
    void refundDatedBeforeTheLevelItWaitedFor() {
        // Order 12 invoiced 50.00 on 1 November; at 23:00 on the 19th in Harare its second level of 50.00 and a credit
        // memo of 80.00. The sync dates that level by the order's last update, which a later update on the 20th
        // pushed past the cut-off, and keeps the memo's own time on the refund: rows of 50.00 (1st), 80.00 back
        // (19th, 21:00 UTC) and 50.00 (20th). The refund waited for the second level, so at the cut-off it had not
        // happened: 50.00 spent, nothing back, as the rows' balance_after says.
        Voucher open = voucher().expiresAt(CUTOFF.plusDays(10)).status(VoucherStatus.EXPIRED).build();
        List<VoucherRedemption> spends = List.of(spend(1, "50.00", LocalDateTime.of(2026, 11, 1, 8, 0), null),
                spend(2, "50.00", LocalDateTime.of(2026, 11, 20, 7, 30), null));
        List<VoucherRefund> refunds = List.of(refund(1, "80.00", LocalDateTime.of(2026, 11, 19, 21, 0), null));

        VoucherAtCutoff at = VoucherAtCutoff.of(open, spends, refunds, CUTOFF, null);

        assertThat(at.spentNet()).isEqualByComparingTo("50.00");
        assertThat(at.refunded()).isEqualByComparingTo("0.00");
        assertThat(at.state()).isEqualTo(VoucherStatus.PARTIALLY_REDEEMED);
        for (UnredeemedVoucherTreatment treatment : UnredeemedVoucherTreatment.values()) {
            assertThat(StaffLoanOwed.at(disbursed(treatment), at).amountToCollect()).as(treatment.name())
                    .isBetween(BigDecimal.ZERO, FACE);
        }
        assertThat(StaffLoanOwed.at(disbursed(UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT), at)
                .amountToCollect()).isEqualByComparingTo("50.00");
        // Stopped by the bank before the cut-off, the debt standing: what was spent, not a 500.
        Voucher stopped = voucher().expiresAt(CUTOFF.minusDays(1)).stoppedReason(VoucherStopReason.BANK_STOPPED)
                .build();
        assertThat(StaffLoanOwed.at(disbursed(UnredeemedVoucherTreatment.DEBT_STANDS),
                VoucherAtCutoff.of(stopped, spends, refunds, CUTOFF, null)).amountToCollect())
                .isEqualByComparingTo("50.00");
        // As known when the payday was finalised: the refund was paid then, the level it waited for was not.
        LocalDate payday = LocalDate.of(2026, 11, 20);
        VoucherAtCutoff known = VoucherAtCutoff.of(open, List.of(spend(1, "50.00",
                        LocalDateTime.of(2026, 11, 1, 8, 0), payday), spend(2, "50.00",
                        LocalDateTime.of(2026, 11, 20, 7, 30), LocalDate.of(2026, 12, 20))),
                List.of(refund(1, "80.00", LocalDateTime.of(2026, 11, 19, 21, 0), payday)), CUTOFF, payday);
        assertThat(known.spentNet()).isEqualByComparingTo("50.00");
        // Had the second level come before the cut-off, the refund would count at its own time.
        assertThat(VoucherAtCutoff.of(open, List.of(spends.getFirst(), spend(2, "50.00",
                        LocalDateTime.of(2026, 11, 19, 21, 30), null)), refunds, CUTOFF, null))
                .satisfies(before -> {
                    assertThat(before.spentNet()).isEqualByComparingTo("20.00");
                    assertThat(before.refunded()).isEqualByComparingTo("80.00");
                });
    }

    @Test
    @DisplayName("a refund counts against its own order's spends only, whole, in time order")
    void refundsMatchTheirOrder() {
        Voucher open = voucher().expiresAt(CUTOFF.plusDays(10)).build();
        List<VoucherRedemption> spends = List.of(spend(1, 12L, "30.00", CUTOFF.minusDays(9), null),
                spend(2, 13L, "60.00", CUTOFF.minusDays(8), null),
                spend(3, 12L, "20.00", CUTOFF.minusDays(2), null));
        List<VoucherRefund> refunds = List.of(refund(1, 12L, "40.00", CUTOFF.minusDays(5), null),
                refund(2, 13L, "10.00", CUTOFF.minusDays(4), null),
                refund(3, 12L, "5.00", CUTOFF.minusHours(1), null));

        VoucherAtCutoff at = VoucherAtCutoff.of(open, spends, refunds, CUTOFF, null);

        // Order 12's 40.00 waits for its second spend (order 13's 60.00 does not cover it), then counts whole; the
        // 5.00 an hour before the cut-off counts at its own time.
        assertThat(at.refunded()).isEqualByComparingTo("55.00");
        assertThat(at.spentNet()).isEqualByComparingTo("55.00");
        assertThat(VoucherAtCutoff.of(open, spends, refunds, CUTOFF.minusDays(3), null)).satisfies(earlier -> {
            assertThat(earlier.refunded()).as("40.00 still waiting for order 12's second spend")
                    .isEqualByComparingTo("10.00");
            assertThat(earlier.spentNet()).isEqualByComparingTo("80.00");
        });
    }

    @Test
    @DisplayName("rows coming to more than the voucher: REDEEMED, the debt stands; reduced, a flagged provisional line")
    void rowsOutOfRange() {
        // A sale dated by its order (ordered before the cut-off, invoiced after another order was refunded after it)
        // can put more than the face value before the cut-off.
        Voucher open = voucher().expiresAt(CUTOFF.plusDays(10)).build();
        VoucherAtCutoff over = VoucherAtCutoff.of(open, List.of(spend(1, 12L, "150.00", CUTOFF.minusDays(9), null),
                spend(2, 13L, "40.00", CUTOFF.minusDays(3), null)), List.of(), CUTOFF, null);
        assertThat(over.state()).isEqualTo(VoucherStatus.REDEEMED);
        assertThat(over.spentNet()).isEqualByComparingTo("190.00");

        StaffLoanOwed debt = StaffLoanOwed.at(disbursed(UnredeemedVoucherTreatment.DEBT_STANDS), over);
        assertThat(debt.amountToCollect()).isEqualByComparingTo("150.00");
        assertThat(debt.finalAmount()).isTrue();
        assertThat(debt.openBalance()).isEqualByComparingTo("0.00");
        StaffLoan reduced = disbursed(UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT);
        assertThatThrownBy(() -> StaffLoanOwed.at(reduced, over)).isInstanceOf(StaffLoanOwed.OutOfRange.class)
                .hasMessage("A payday amount of 190.00 is outside 0 to 150.00");

        StaffLoanOwed flagged = StaffLoanOwed.inconsistent(reduced.getTotalRepayable(), over);
        assertThat(flagged.amountToCollect()).isEqualByComparingTo("150.00");
        assertThat(flagged.finalAmount()).isFalse();
        assertThat(flagged.couldRiseTo()).isEqualByComparingTo("150.00");
        assertThat(flagged.openBalance()).isEqualByComparingTo("0.00");
        assertThat(flagged.flags()).containsExactly(Flag.ROWS_INCONSISTENT);
        VoucherAtCutoff below = new VoucherAtCutoff(9L, VoucherStatus.PARTIALLY_REDEEMED, null, "USD", FACE,
                new BigDecimal("-30.00"), new BigDecimal("80.00"), new BigDecimal("5.00"), CUTOFF.minusDays(1), null,
                true);
        assertThat(StaffLoanOwed.inconsistent(reduced.getTotalRepayable(), below)).satisfies(owed -> {
            assertThat(owed.amountToCollect()).isEqualByComparingTo("0.00");
            assertThat(owed.openBalance()).isEqualByComparingTo("150.00");
            assertThat(owed.flags()).containsExactly(Flag.STALE, Flag.HELD_SALES, Flag.ROWS_INCONSISTENT);
        });
    }
}
