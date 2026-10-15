package zw.co.innbucks.loans.core.getmore;

import java.util.List;

/**
 * GetMore's Magento Open Source 2.4 store: the eight REST calls loans makes, in Magento's semantics, and nothing else.
 * Everything that decides anything (find-or-create, reading spends and refunds, withdrawing) lives above this port and
 * runs unchanged in every {@link GetMoreMode}; only the implementation behind it changes.
 *
 * <p>Implementations call {@link GetMoreCalls#requireNoTransaction()} first ({@link IllegalStateException} if a
 * transaction is open) and NEVER retry. Failures are {@link GetMoreRejectedException} (nothing applied),
 * {@link GetMoreUnavailableException} (not processed, or a read failed) and {@link GetMoreOutcomeUnknownException}
 * (a write may have applied).
 */
public interface GetMoreStore {

    GetMoreMode mode();

    /**
     * {@code POST /V1/coupons/generate}. Returns the codes only (Magento returns no coupon_id). Never retried.
     *
     * @throws GetMoreRejectedException       nothing minted
     * @throws GetMoreUnavailableException    not processed
     * @throws GetMoreOutcomeUnknownException a coupon may exist: search the prefix before anything else
     */
    List<String> generateCoupons(GetMoreCouponSpec spec);

    /**
     * {@code GET /V1/coupons/search}: {@code code like '<prefix>%'} (re-filtered client-side), or {@code code eq}
     * (owner-requested; it puts a full code in GetMore's access log, so no loans caller uses it:
     * {@code GetMoreCodeInUrlTest}), sorted {@code coupon_id ASC}.
     */
    GetMorePage<GetMoreCoupon> searchCoupons(GetMoreCouponQuery query);

    /**
     * {@code GET /V1/coupons/{couponId}}.
     *
     * @throws GetMoreNotFoundException no such coupon (or it was deleted)
     */
    GetMoreCoupon coupon(long couponId);

    /** {@code PUT /V1/coupons/{couponId}} {@code {"coupon":{coupon_id, rule_id, code, type, expiration_date}}}. Never retried. */
    GetMoreCoupon updateCoupon(long couponId, GetMoreCouponUpdate update);

    /**
     * {@code POST /V1/coupons/deleteByCodes} {@code {"codes":[...], "ignoreInvalidCoupons":bool}}. Never retried; a
     * repeat reports the code as missing. Send codes exactly as stored: Magento compares strings exactly.
     */
    GetMoreCouponDeletion deleteCoupons(List<String> codesAsStored, boolean ignoreInvalidCoupons);

    /**
     * {@code GET /V1/salesRules/{ruleId}}.
     *
     * @throws GetMoreNotFoundException no such rule
     */
    GetMoreSalesRule salesRule(long ruleId);

    /**
     * {@code GET /V1/orders}: {@code coupon_code like} (or {@code eq}, owner-requested, unused by loans), an optional
     * {@code created_at} window, sorted {@code entity_id ASC}.
     */
    GetMorePage<GetMoreOrder> orders(GetMoreOrderQuery query);

    /** {@code GET /V1/creditmemos}: {@code order_id in} (at most 100 ids), sorted {@code entity_id ASC}. */
    GetMorePage<GetMoreCreditMemo> creditMemos(GetMoreCreditMemoQuery query);
}
