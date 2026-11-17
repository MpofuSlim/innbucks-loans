package zw.co.innbucks.loans.core.disbursements;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.config.HttpLogRedactor;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An InnBucks answer as it is kept with a staff loan's payout (its last answer, the evidence an operator decides on):
 * redacted by the log rules ({@link HttpLogRedactor}: credentials, ID numbers and account numbers masked), every phone
 * number and the value of any other field named an account masked to its last four characters ({@code ****3123}), and
 * cut to {@link #MAX_CHARS}. Anyone who can read staff loans reads it, so it names nobody's number in full.
 */
public final class BankAnswers {

    /** The longest answer kept: the size of {@code staff_loans.payout_last_answer}. */
    public static final int MAX_CHARS = 2000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The value of a field whose name says it is a phone ({@code msisdn}, {@code mobileNumber}, {@code phone}, any case,
     * any prefix): a JSON string, or a bare number.
     */
    private static final Pattern PHONE_FIELD = Pattern.compile(
            "(\"[A-Za-z0-9_-]*(?i:msisdn|mobile|phone|cell)[A-Za-z0-9_-]*\"\\s*:\\s*)(\"[^\"]*\"|[0-9+]+)");
    /**
     * The value of a field whose name says it is an account the log rules do not already mask ({@code account},
     * {@code accountNo}, {@code walletAccount}, any case): a JSON string, or a bare number. The bank's answers have not
     * been recorded yet, so what it calls the account it paid is not known.
     */
    private static final Pattern ACCOUNT_FIELD = Pattern.compile(
            "(\"[A-Za-z0-9_-]*(?i:account)[A-Za-z0-9_-]*\"\\s*:\\s*)(\"[^\"]*\"|[0-9]+)");
    /** A Zimbabwean mobile number wherever it stands: 263772123123, +263 77 212 3123 written together, 0772123123. */
    private static final Pattern ZIMBABWE_MOBILE = Pattern.compile("(?<![0-9])(?:\\+?263|0)?7[13789][0-9]{7}(?![0-9])");

    private BankAnswers() {
    }

    /** The answer redacted, its phone numbers masked, and cut short; null for none. */
    static String kept(String body, HttpHeaders headers) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        MediaType contentType = null;
        try {
            MediaType declared = headers == null ? null : headers.getContentType();
            // Without its charset: the body is already text, and is re-encoded as UTF-8 below.
            contentType = declared == null ? null : new MediaType(declared.getType(), declared.getSubtype());
        } catch (RuntimeException unreadable) {
            // An unparseable Content-Type: redact the body as text.
        }
        String redacted = maskAccounts(maskPhones(HttpLogRedactor.redactBody(body.getBytes(StandardCharsets.UTF_8),
                contentType)));
        return redacted.length() <= MAX_CHARS ? redacted : redacted.substring(0, MAX_CHARS);
    }

    /** Every phone in {@code text} as its last four digits: the value of a phone field, and any Zimbabwean mobile. */
    static String maskPhones(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher field = PHONE_FIELD.matcher(text);
        StringBuilder fields = new StringBuilder();
        while (field.find()) {
            String value = field.group(2);
            boolean quoted = value.startsWith("\"");
            String digits = quoted ? value.substring(1, value.length() - 1) : value;
            String masked = digits.isBlank() || HttpLogRedactor.MASK.equals(digits) ? digits
                    : MsisdnUtils.mask(digits);
            field.appendReplacement(fields, Matcher.quoteReplacement(field.group(1) + "\"" + masked + "\""));
        }
        field.appendTail(fields);
        Matcher mobile = ZIMBABWE_MOBILE.matcher(fields);
        StringBuilder masked = new StringBuilder();
        while (mobile.find()) {
            mobile.appendReplacement(masked, Matcher.quoteReplacement(MsisdnUtils.mask(mobile.group())));
        }
        mobile.appendTail(masked);
        return masked.toString();
    }

    /**
     * The value of every field named an account that holds a digit, as its last four characters:
     * {@code "walletAccount":"****7890"}.
     */
    static String maskAccounts(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher field = ACCOUNT_FIELD.matcher(text);
        StringBuilder masked = new StringBuilder();
        while (field.find()) {
            String value = field.group(2);
            String account = value.startsWith("\"") ? value.substring(1, value.length() - 1) : value;
            // A value with no digit (an account type, a name) is no account number, and stays readable.
            String kept = account.chars().noneMatch(Character::isDigit) || account.startsWith("****") ? account
                    : "****" + (account.length() <= 4 ? "" : account.substring(account.length() - 4));
            field.appendReplacement(masked, Matcher.quoteReplacement(field.group(1) + "\"" + kept + "\""));
        }
        field.appendTail(masked);
        return masked.toString();
    }

    /**
     * The {@code responseCode} of InnBucks' envelope, as text (a number written as one becomes its digits); null when
     * {@code body} is not a JSON object or carries none.
     */
    static String responseCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode code = root.path("responseCode");
            if (!code.isValueNode() || code.isNull()) {
                return null;
            }
            String value = code.asString().strip();
            return value.isEmpty() ? null : value;
        } catch (RuntimeException notJson) {
            return null;
        }
    }
}
