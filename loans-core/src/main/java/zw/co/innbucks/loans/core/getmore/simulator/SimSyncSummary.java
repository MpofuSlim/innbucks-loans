package zw.co.innbucks.loans.core.getmore.simulator;

import zw.co.innbucks.loans.core.voucher.GetMoreSyncResult;
import zw.co.innbucks.loans.core.voucher.VoucherSyncIssueResponse;

import java.math.BigDecimal;
import java.util.List;

/** What the loans sync a staging test action ran afterwards did, in brief: the voucher itself is beside it. */
public record SimSyncSummary(GetMoreSyncResult.Outcome outcome, int spendsRecorded, int refundsRecorded,
                             BigDecimal heldAmount, List<VoucherSyncIssueResponse> issues) {

    static SimSyncSummary of(GetMoreSyncResult result) {
        return result == null ? null : new SimSyncSummary(result.outcome(), result.spendsRecorded(),
                result.refundsRecorded(), result.heldAmount(), result.issues());
    }
}
