package zw.co.innbucks.loans.core.borrower;

import zw.co.innbucks.loans.core.MsisdnUtils;

import java.util.Optional;

/**
 * A phone from a middleware assertion as the staff register writes it, 2637XXXXXXXX, however the middleware wrote it.
 */
public final class BorrowerPhones {

    private BorrowerPhones() {
    }

    /** Empty for anything that is not a Zimbabwean mobile number. */
    public static Optional<String> asRegistered(String phone) {
        if (phone == null) {
            return Optional.empty();
        }
        String stripped = phone.strip();
        return stripped.matches(MsisdnUtils.ZIMBABWE_MOBILE_REGEX)
                ? Optional.of(MsisdnUtils.formatMsisdnInternational(stripped)) : Optional.empty();
    }
}
