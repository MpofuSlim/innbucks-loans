package zw.co.innbucks.loans.core.staff.loan;

import com.fasterxml.jackson.annotation.JsonProperty;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;
import zw.co.innbucks.loans.core.voucher.VoucherStopReason;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * What is owed on a Staff Grocery Loan payday, and what is owed to GetMore for it: the statement payroll collects from
 * and finance settles with. Each loan due that payday has a line saying what to collect from salary, under the
 * treatment it was accepted under, as its GetMore voucher stood at the cut-off (00:00 market time on the payday), and
 * whether that amount is final ({@link StaffLoanOwed}). The GetMore side is every sale and refund of the store's
 * vouchers made before the cut-off and not paid for by an earlier payday, whichever loan it belongs to.
 *
 * <p>Before the payday is finalised this is a preview, recomputed on every read. Once finalised the lines are the
 * amounts sent to payroll, kept as they were, and the GetMore side is the rows that payday paid for; a sale or refund
 * made before the cut-off but recorded after is listed in {@code lateAdjustments} and paid in the next payday, where
 * it shows {@code late}.</p>
 *
 * @param cutoff          00:00 market time on the payday, UTC
 * @param storeMode       the GetMore store it describes: SIMULATED on staging's simulated host, LIVE everywhere else
 * @param simulated       whether it describes staging's simulated store: a test, not for payroll
 * @param finalised       whether the payday was finalised in this store
 * @param finalisedBy     who finalised it; null until then
 * @param finalisationReason why it was finalised with anything not final; null when nothing was, or until then
 * @param lines           the loans due that payday, by loan
 * @param totals          per currency
 * @param excluded        loans due that payday collected elsewhere or not at all here
 * @param lateAdjustments once finalised: GetMore rows of these loans' vouchers made before the cut-off but recorded
 *                        after it was finalised, per loan
 * @param staleLines      lines not read from GetMore since the cut-off
 * @param openSyncIssues  open GetMore sync issues on the lines' vouchers
 */
public record StaffLoanCollectionReport(LocalDate payday, LocalDateTime cutoff, GetMoreMode storeMode,
                                        boolean simulated, boolean finalised, LocalDateTime finalisedAt,
                                        String finalisedBy, String finalisationReason, List<Line> lines,
                                        List<Totals> totals, MerchantSettlement merchantSettlement,
                                        List<Excluded> excluded, List<LateAdjustment> lateAdjustments,
                                        int staleLines, long openSyncIssues) {

    /**
     * One loan due on the payday.
     *
     * @param status               the loan's status now
     * @param voucherId            its GetMore voucher; null when none
     * @param voucherStateAtCutoff the voucher's state at the cut-off; null when none
     * @param stoppedReason        why the voucher was stopped early, when it had expired by the cut-off
     * @param spentNet             spent before the cut-off less refunded before it
     * @param refunded             refunded before the cut-off
     * @param held                 ordered at GetMore and not invoiced, at the voucher's last sync
     * @param openBalance          still spendable at the cut-off
     * @param couldRiseTo          the most a provisional amount could become; null when final or unknown
     * @param finalAmount          whether {@code amountToCollect} is final ({@code final} on the wire)
     * @param lastSyncedAt         when the voucher was last read from GetMore
     * @param simulated            paid out as a coupon of staging's simulated store
     */
    public record Line(Long staffLoanId, String loanReference, String employeeNumber, String fullName,
                       StaffLoanStatus status, UnredeemedVoucherTreatment treatment, Long voucherId,
                       VoucherStatus voucherStateAtCutoff, VoucherStopReason stoppedReason, String currency,
                       BigDecimal faceValue, BigDecimal spentNet, BigDecimal refunded, BigDecimal held,
                       BigDecimal openBalance, BigDecimal amountToCollect, BigDecimal couldRiseTo,
                       @JsonProperty("final") boolean finalAmount, List<StaffLoanOwed.Flag> flags,
                       LocalDateTime lastSyncedAt, boolean simulated) {
    }

    /** One currency's lines: how many, what to collect, and how much of it is provisional. */
    public record Totals(String currency, int loans, BigDecimal amountToCollect, int provisionalLoans,
                         BigDecimal provisionalAmount) {
    }

    /**
     * What the payday pays GetMore: its sales less its refunds, per voucher and per currency. May be negative (more
     * refunded than sold in the cycle); never clamped.
     *
     * @param previousCycle the previous payday in this store, when it was finalised
     * @param staleVouchers vouchers whose sales or refunds can fall in the cycle not read from GetMore since the
     *                      cut-off (as at finalisation, once finalised)
     */
    public record MerchantSettlement(String merchantCode, String merchantName, LocalDateTime cutoff,
                                     PreviousCycle previousCycle, int staleVouchers,
                                     List<SettlementTotals> totals, List<SettlementLine> lines) {
    }

    public record PreviousCycle(LocalDate payday, LocalDateTime finalisedAt) {
    }

    public record SettlementTotals(String currency, BigDecimal spends, BigDecimal refunds, BigDecimal net) {
    }

    /**
     * One voucher's rows in the cycle.
     *
     * @param late made before the previous payday's cut-off but recorded after that payday was finalised: paid now
     */
    public record SettlementLine(Long voucherId, String loanReference, String currency, BigDecimal spends,
                                 BigDecimal refunds, BigDecimal net, boolean late) {
    }

    /** Why a loan due on the payday has no line. */
    public enum ExclusionReason {
        /** Written off: still owed, recovered outside payroll. */
        WRITTEN_OFF,
        /** Paid out as a coupon of staging's simulated store, on a host that is not: a test. */
        SIMULATED,
        /** Not a line of the payday when it was finalised (it was written off or not due then). */
        NOT_IN_FINALISED_CYCLE
    }

    /** A loan due on the payday with no line, and what it still owes. */
    public record Excluded(Long staffLoanId, String loanReference, String employeeNumber, StaffLoanStatus status,
                           ExclusionReason reason, String currency, BigDecimal outstanding) {
    }

    /** A loan's GetMore rows that changed after its payday was finalised: sales and refunds made before the cut-off. */
    public record LateAdjustment(Long staffLoanId, String loanReference, Long voucherId, String currency,
                                 BigDecimal spends, BigDecimal refunds, BigDecimal net) {
    }

    /** The lines as a spreadsheet, and what names the file. */
    public record Csv(LocalDate payday, boolean simulated, String content) {

        public String fileName() {
            return "staff-loan-collections-" + payday + (simulated ? "-SIMULATED" : "") + ".csv";
        }
    }

    /**
     * What one page of a payday sync did.
     *
     * @param unavailable vouchers GetMore could not be read for (or another sync of was running)
     * @param cleanedUp   cancelled loans' voucher issuances whose coupons were withdrawn (first page only)
     * @param nextAfter   the {@code after} of the next page; null when this was the last
     */
    public record Sync(int synced, int unchanged, int blocked, int unavailable, int cleanedUp, Long nextAfter) {
    }
}
