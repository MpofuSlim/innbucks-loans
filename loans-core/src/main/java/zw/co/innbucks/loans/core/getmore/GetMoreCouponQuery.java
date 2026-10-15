package zw.co.innbucks.loans.core.getmore;

/**
 * A page of {@code GET /V1/coupons/search}, by exactly one of a code ({@code code eq}) or a prefix
 * ({@code code like '<prefix>%'}). Pages count from 1 and Magento allows at most 300 per page; the live client refuses
 * anything else before the network, and the simulator answers it as Magento would.
 */
public record GetMoreCouponQuery(String code, String prefix, int page, int pageSize) {

    public GetMoreCouponQuery {
        if (isBlank(code) == isBlank(prefix)) {
            throw new IllegalArgumentException("A coupon search names exactly one of a code or a prefix");
        }
    }

    /**
     * Owner-requested and unused by loans: a full code in the query string reaches GetMore's access logs. Every loans
     * caller searches by prefix ({@code GetMoreCodeInUrlTest}).
     */
    public static GetMoreCouponQuery byCode(String code, int page, int pageSize) {
        return new GetMoreCouponQuery(code, null, page, pageSize);
    }

    /** Page from 1; page size 1..300. */
    public static GetMoreCouponQuery byPrefix(String prefix, int page, int pageSize) {
        return new GetMoreCouponQuery(null, prefix, page, pageSize);
    }

    /** Never the code: {@code ****8B1C}. */
    @Override
    public String toString() {
        return "GetMoreCouponQuery[" + (code != null ? "code=" + GetMoreCodes.maskedCode(code) : "prefix=" + prefix)
                + ", page=" + page + ", pageSize=" + pageSize + "]";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
