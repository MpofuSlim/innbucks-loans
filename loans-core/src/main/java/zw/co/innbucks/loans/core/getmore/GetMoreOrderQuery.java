package zw.co.innbucks.loans.core.getmore;

import java.time.LocalDateTime;

/**
 * A page of {@code GET /V1/orders} by exactly one of a coupon code ({@code coupon_code eq}) or a coupon prefix
 * ({@code coupon_code like '<prefix>%'}), optionally within {@code [createdFromUtc, createdToUtc)}.
 */
public record GetMoreOrderQuery(String couponCode, String couponPrefix, LocalDateTime createdFromUtc,
                                LocalDateTime createdToUtc, int page, int pageSize) {

    public GetMoreOrderQuery {
        if (isBlank(couponCode) == isBlank(couponPrefix)) {
            throw new IllegalArgumentException("An order search names exactly one of a coupon code or a coupon prefix");
        }
    }

    /** Every order on a coupon prefix, with no date window: what a sync reads. */
    public static GetMoreOrderQuery byPrefix(String couponPrefix, int page, int pageSize) {
        return new GetMoreOrderQuery(null, couponPrefix, null, null, page, pageSize);
    }

    /**
     * Owner-requested and unused by loans: a full code in the query string reaches GetMore's access logs
     * ({@code GetMoreCodeInUrlTest}).
     */
    public static GetMoreOrderQuery byCode(String couponCode, int page, int pageSize) {
        return new GetMoreOrderQuery(couponCode, null, null, null, page, pageSize);
    }

    /** Never the code: {@code ****8B1C}. */
    @Override
    public String toString() {
        return "GetMoreOrderQuery[" + (couponCode != null ? "couponCode=" + GetMoreCodes.maskedCode(couponCode)
                : "couponPrefix=" + couponPrefix) + ", createdFromUtc=" + createdFromUtc + ", createdToUtc="
                + createdToUtc + ", page=" + page + ", pageSize=" + pageSize + "]";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
