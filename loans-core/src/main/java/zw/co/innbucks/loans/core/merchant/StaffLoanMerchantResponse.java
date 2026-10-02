package zw.co.innbucks.loans.core.merchant;

import org.apache.commons.lang3.StringUtils;

/**
 * The Staff Grocery Loan's merchant.
 *
 * @param settlementAccountConfigured whether the merchant has an account to be paid into; until it does, no loan for
 *                                    it can be paid out
 */
public record StaffLoanMerchantResponse(String merchantCode, String name, boolean settlementAccountConfigured) {

    public static StaffLoanMerchantResponse of(Merchant merchant) {
        return new StaffLoanMerchantResponse(merchant.getMerchantCode(), merchant.getCompanyName(),
                StringUtils.isNotBlank(merchant.getAccountNumber()));
    }
}
