package zw.co.reikan.nanoloansweb;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MsisdnUtil {

    private static final String MSISDN_REGEX_EXPRESSION = "(((\\+|00)?268)|0)?7\\d{7}";

    private static final int MINIMUM_MSISDN_LENGTH = 9;

    private static final String INTERNATIONAL_CODE = "263";

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

    public static String formatMsisdnInternational(String msisdn) {
        return INTERNATIONAL_CODE + formatMsisdnMinimum(msisdn);
    }

    public boolean isValidMsisdn(final String msisdn) {

        return !StringUtils.isEmpty(msisdn) && msisdn.matches(MSISDN_REGEX_EXPRESSION);
    }


}
