package zw.co.reikan.loans.core;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MsisdnUtil {

    private static final String MSISDN_REGEX_EXPRESSION = "(((\\+|00)?268)|0)?7\\d{7}";

    private static final int MINIMUM_MSISDN_LENGTH = 9;

    private static final String INTERNATIONAL_CODE = "263";

    /**
     * A Zimbabwean MOBILE number, in any of the forms people type:
     * {@code 0772123123}, {@code 772123123}, {@code 263772123123} or
     * {@code +263772123123}. Mobile prefixes only — 71 NetOne, 73 Telecel,
     * 77/78 Econet — because these numbers receive SMS and InnBucks wallet
     * credits. Deliberately NOT {@link #MSISDN_REGEX_EXPRESSION}, which is
     * Eswatini's {@code 268} with an 8-digit body and matches no Zimbabwean
     * number at all.
     *
     * <p>Before this, the loan application took ANY string and kept its last
     * nine characters ({@link #formatMsisdnMinimum}), so a typo became a wrong
     * but well-formed number on the loan and at InnBucks.
     */
    public static final String ZIMBABWE_MOBILE_REGEX = "^(?:\\+?263|0)?7[1378]\\d{7}$";
    public static final String ZIMBABWE_MOBILE_MESSAGE =
            "must be a Zimbabwean mobile number, e.g. 0772123123 or +263772123123";

    public static void validateMsisdn(final String msisdn) {

        final boolean isValid = !StringUtils.isEmpty(msisdn) && msisdn.matches(MSISDN_REGEX_EXPRESSION);
        if (!isValid) {
            throw new IllegalArgumentException("Invalid msisdn " + msisdn);
        }
    }

    public static String formatMsisdnMinimum(final String msisdn) {

        if (StringUtils.isEmpty(msisdn)) {
            throw new IllegalArgumentException("Invalid mobile number. Mobile number cannot be empty");
        }
        final String trimmedMsisdn = msisdn.trim();
        if (trimmedMsisdn.length() <= MINIMUM_MSISDN_LENGTH) {
            return trimmedMsisdn;
        }
        return trimmedMsisdn.substring(trimmedMsisdn.length() - MINIMUM_MSISDN_LENGTH);
    }

    /**
     * The last four digits of {@code msisdn} — how customer-facing text names a
     * wallet ("wallet ending 6983") without printing the whole number. Non-digits
     * are ignored, so every stored spelling ({@code 0782606983},
     * {@code +263782606983}) masks alike.
     */
    public static String lastFourDigits(final String msisdn) {

        final String digits = msisdn == null ? "" : msisdn.replaceAll("\\D", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

    public static String formatMsisdnInternational(String msisdn) {
        return INTERNATIONAL_CODE + formatMsisdnMinimum(msisdn);
    }

    public boolean isValidMsisdn(final String msisdn) {

        return !StringUtils.isEmpty(msisdn) && msisdn.matches(MSISDN_REGEX_EXPRESSION);
    }


}
