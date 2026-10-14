package zw.co.innbucks.loans.core.getmore;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The fields of a Magento cart price rule ({@code GET /V1/salesRules/{id}}) that decide whether it can back a voucher.
 * {@code discountAmount} is exactly as Magento sent it ({@code 50}, {@code 50.0} and {@code 50.0000} compare equal);
 * {@code usesPerCoupon} 0 means unlimited; {@code couponType} is {@code NO_COUPON}, {@code SPECIFIC_COUPON} or
 * {@code AUTO}.
 */
public record GetMoreSalesRule(long ruleId, String name, boolean active, LocalDate fromDate, LocalDate toDate,
                               String simpleAction, BigDecimal discountAmount, String couponType,
                               boolean useAutoGeneration, int usesPerCoupon, int usesPerCustomer,
                               boolean stopRulesProcessing, boolean applyToShipping) {
}
