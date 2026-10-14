package zw.co.innbucks.loans.core.voucher;

import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.getmore.GetMoreCodes;
import zw.co.innbucks.loans.core.getmore.GetMoreMode;

import java.math.BigDecimal;

/**
 * A GetMore coupon to keep as a Staff Grocery Loan's voucher, once GetMore's store has made it and it is bound to the
 * loan's value ({@link VoucherService#issueFromGetMore}).
 *
 * @param codeAsStored the code exactly as GetMore returned it: what is sealed, sent and shown; its fingerprint is taken
 *                     of the normalised form
 * @param codePrefix   the issuance's own prefix, nonce included
 */
public record GetMoreVoucherCommand(Long staffLoanId, String loanAccount, Long merchantId, Long staffMemberId,
                                    String customerReference, String customerName, String customerMsisdn,
                                    BigDecimal faceValue, String currency, GetMoreMode storeMode, Long couponId,
                                    Long ruleId, String codeAsStored, String codePrefix) {

    /** Never the code ({@code ****8B1C}) nor the whole number. */
    @Override
    public String toString() {
        return "GetMoreVoucherCommand[staffLoanId=" + staffLoanId + ", loanAccount=" + loanAccount + ", merchantId="
                + merchantId + ", staffMemberId=" + staffMemberId + ", customerReference=" + customerReference
                + ", customerMsisdn=" + MsisdnUtils.mask(customerMsisdn) + ", faceValue=" + faceValue + ", currency="
                + currency + ", storeMode=" + storeMode + ", couponId=" + couponId + ", ruleId=" + ruleId + ", code="
                + GetMoreCodes.maskedCode(codeAsStored) + ", codePrefix=" + codePrefix + "]";
    }
}
