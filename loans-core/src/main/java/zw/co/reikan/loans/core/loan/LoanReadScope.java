package zw.co.reikan.loans.core.loan;

/**
 * Which loans a caller may READ. A loan carries the borrower's full KYC (national
 * id, EC number, bank details, next of kin, ID/payslip/signature images), so every
 * read path that is not lender-side staff is narrowed to the caller's own merchant
 * and, for field agents, to the loans they originated.
 *
 * <p>Platform-wide is an explicit flag, and a narrowed scope refuses to exist without
 * a merchant code, because {@link LoanSpecification#withMerchantCode} treats a blank
 * code as "no filter": a scope that lost its code would silently read every merchant.
 *
 * @param platformWide every merchant's loans — lender-side staff only
 * @param merchantCode the caller's merchant; null only when platform-wide
 * @param userId       when set, only loans created by or agented by this user
 */
public record LoanReadScope(boolean platformWide, String merchantCode, Long userId) {

    public LoanReadScope {
        if (!platformWide && (merchantCode == null || merchantCode.isBlank())) {
            throw new IllegalArgumentException("A merchant-scoped loan read needs a merchant code");
        }
    }

    public static LoanReadScope platform() {
        return new LoanReadScope(true, null, null);
    }

    public static LoanReadScope merchant(String merchantCode) {
        return new LoanReadScope(false, merchantCode, null);
    }

    public static LoanReadScope originator(String merchantCode, Long userId) {
        return new LoanReadScope(false, merchantCode, userId);
    }
}
