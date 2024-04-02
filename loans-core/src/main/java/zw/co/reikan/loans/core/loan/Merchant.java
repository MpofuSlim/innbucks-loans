package zw.co.reikan.loans.core.loan;

import static zw.co.reikan.loans.core.loan.DisbursementType.CUSTOMER_MOBILE_WALLET;
import static zw.co.reikan.loans.core.loan.DisbursementType.MERCHANT_MOBILE_WALLET;

public enum Merchant {
    INNBUCKS(CUSTOMER_MOBILE_WALLET),
    HOUSE_AND_HOME(MERCHANT_MOBILE_WALLET),
    CAPRI(MERCHANT_MOBILE_WALLET);

    final DisbursementType disbursementType;

    Merchant(DisbursementType disbursementType) {
        this.disbursementType = disbursementType;
    }
}
