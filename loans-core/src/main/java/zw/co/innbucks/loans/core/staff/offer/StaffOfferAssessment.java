package zw.co.innbucks.loans.core.staff.offer;

import zw.co.innbucks.loans.core.staff.StaffGradeLimit;

import java.math.BigDecimal;

/**
 * Where one member of the register stands for a Staff Grocery Loan offer right now.
 *
 * @param verdict         whether they may be offered, and if not why
 * @param unavailable     why no offer can be made to anyone at all just now (the register is not reconciled recently
 *                        enough), or null when offers can be made
 * @param limit           their grade's limit in force today, if any
 * @param override        Credit's limit override in force for them, if any
 * @param arrearsOverride Credit's arrears override that lets them borrow despite a written-off balance (FR-SGL-014):
 *                        set only when they are ELIGIBLE because of it; a loan they accept uses it up
 */
public record StaffOfferAssessment(StaffOfferVerdict verdict, String unavailable, StaffGradeLimit limit,
                                   StaffLimitOverride override, StaffArrearsOverride arrearsOverride) {

    /** The amount an offer to them is made at: Credit's override when there is one, else their grade's limit. */
    public BigDecimal amount() {
        return override != null ? override.getAmount() : limit.maximumLimit();
    }
}
