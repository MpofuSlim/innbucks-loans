package zw.co.innbucks.loans.core.getmore;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.merchant.VoucherStore;

import java.util.List;
import java.util.Map;

/**
 * Where GetMore stands on this host, for Credit, Finance and the super-admin: the mode and its settings, the Staff
 * Grocery Loan's merchant and whether it is a GetMore store, anything that needs attention ({@code warnings}), how many
 * voucher issuances are open in each state, and how many vouchers a sync found a problem with or holds a sale for. The
 * counts are of the store this host is in (LIVE when GetMore is OFF): rows of another store are never counted.
 *
 * @param couponPrefix    the configured prefix codes are issued under in this mode (each issuance adds its own nonce)
 * @param liveHost        GetMore's host when LIVE, otherwise null
 * @param amountModel     how a coupon's value is held: NONE, RULE_AMOUNT_SINGLE_USE or SIMULATED_PER_COUPON
 * @param issuances       open issuances by status: PENDING, CALLING, PARKED and CANCELLING
 * @param openSyncIssues  sync issues not yet cleared, blocking or not
 * @param blockedVouchers vouchers whose last sync stopped at a blocking issue
 * @param heldVouchers    vouchers with a sale ordered at GetMore and not yet invoiced
 * @param rule            the sales rule as GetMore holds it, only when asked for ({@code checkRule=true}) and read
 * @param ruleError       why the rule could not be read, when asked for: the error code and its reason
 */
public record GetMoreStatusResponse(GetMoreMode mode, long ruleId, String couponPrefix, String liveHost,
                                    String amountModel, boolean partialSpending, StaffLoanMerchant staffLoanMerchant,
                                    List<String> warnings, Map<String, Long> issuances, long openSyncIssues,
                                    long blockedVouchers, long heldVouchers,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) GetMoreSalesRule rule,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) String ruleError) {

    /** The Staff Grocery Loan's merchant and whose codes its vouchers carry; null when none is set. */
    public record StaffLoanMerchant(String merchantCode, VoucherStore voucherStore) {
    }
}
