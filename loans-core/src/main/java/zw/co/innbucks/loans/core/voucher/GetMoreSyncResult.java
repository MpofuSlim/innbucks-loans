package zw.co.innbucks.loans.core.voucher;

import java.math.BigDecimal;
import java.util.List;

/**
 * What one GetMore sync of a voucher did.
 *
 * @param spendsRecorded     spend rows added
 * @param refundsRecorded    refund rows added
 * @param heldAmount         ordered at GetMore and not yet invoiced, as now recorded; null when nothing was read
 * @param liveOrdersOnCoupon the voucher's own orders still standing at GetMore, invoiced or not
 * @param issues             the voucher's open sync issues after it
 * @param couponWithdrawn    whether the voucher's code is gone at GetMore, as now recorded
 * @param voucher            the voucher after it; null when it was never read ({@link Outcome#BUSY})
 */
public record GetMoreSyncResult(Outcome outcome, int spendsRecorded, int refundsRecorded, BigDecimal heldAmount,
                                int liveOrdersOnCoupon, List<VoucherSyncIssueResponse> issues,
                                boolean couponWithdrawn, VoucherResponse voucher) {

    public enum Outcome {
        /** Read and applied: rows were added, or the voucher changed (held, stopped, withdrawn). */
        SYNCED,
        /** Read in full; nothing new. */
        UNCHANGED,
        /** Read and applied up to a blocking issue; nothing after it was recorded ({@code syncBlockedBy}). */
        BLOCKED,
        /** A newer sync was applied first; this one's snapshot was discarded. */
        STALE_SNAPSHOT,
        /** A row it was adding was added at the same moment by another writer; nothing written, sync again. */
        CONFLICT_RETRY,
        /** The voucher's store is not this host's GetMore mode; nothing read or written. */
        MODE_MISMATCH,
        /** Not a GetMore voucher. */
        SKIPPED,
        /** GetMore could not be read; nothing written. */
        UNAVAILABLE,
        /** Another sync of this voucher is running. */
        BUSY;

        /** Whether GetMore was read in full and what it said is now recorded, up to a blocking issue if any. */
        public boolean read() {
            return this == SYNCED || this == UNCHANGED || this == BLOCKED;
        }
    }

    public GetMoreSyncResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** Nothing read or written. */
    static GetMoreSyncResult nothing(Outcome outcome, VoucherResponse voucher) {
        return new GetMoreSyncResult(outcome, 0, 0, null, 0, List.of(), voucher != null
                && voucher.couponWithdrawnAt() != null, voucher);
    }

    @Override
    public String toString() {
        return "GetMoreSyncResult[outcome=" + outcome + ", voucherId=" + (voucher == null ? null : voucher.id())
                + ", spendsRecorded=" + spendsRecorded + ", refundsRecorded=" + refundsRecorded + ", heldAmount="
                + heldAmount + ", liveOrdersOnCoupon=" + liveOrdersOnCoupon + ", issues=" + issues.size()
                + ", couponWithdrawn=" + couponWithdrawn + "]";
    }
}
