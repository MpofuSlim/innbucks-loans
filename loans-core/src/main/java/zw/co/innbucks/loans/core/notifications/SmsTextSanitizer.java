package zw.co.innbucks.loans.core.notifications;

import java.text.Normalizer;

/**
 * Makes SMS text safe for the InnBucks notification API, which refuses a message carrying a character outside the
 * set it accepts with {@code 400 "Invalid message"}. A copy of the ticketing fleet's sanitizer (user-service
 * {@code SmsTextSanitizer}); keep the two in step, because both send through the same API.
 *
 * <p>Typographic punctuation (dashes, curly quotes, ellipsis, non-breaking space, bullet) becomes its ASCII
 * equivalent, accented letters lose their accents, the characters the API refuses ({@code ! : / ? " * ;}) become
 * {@code .} or a space, and anything else outside the accepted set becomes a space.
 *
 * <p><b>SMS only.</b> WhatsApp and email render Unicode and keep the original text.
 */
public final class SmsTextSanitizer {

    private SmsTextSanitizer() {
    }

    /** A form of {@code text} the API accepts. Null and empty pass through unchanged. */
    public static String toGsmSafe(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text
                .replace("–", "-").replace("—", "-")   // en dash, em dash
                .replace("‒", "-").replace("―", "-")   // figure dash, horizontal bar
                .replace("‘", "'").replace("’", "'")   // curly single quotes
                .replace("‚", "'").replace("‛", "'")
                // Curly double quotes become an apostrophe, not '"': the API refuses the double quote.
                .replace("“", "'").replace("”", "'")
                .replace("„", "'")
                .replace("…", "...")                        // ellipsis
                .replace(" ", " ")                          // non-breaking space
                // A bullet becomes '-', not '*': the API refuses '*' too.
                .replace("•", "-").replace("·", ".");
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        // Refused by the API (probed one character at a time against the live gateway, 2026-07-29): ! : / ? " * ;
        // Sentence enders become '.', the rest a space so words never run together.
        s = s.replace('!', '.').replace('?', '.').replace(';', '.')
                .replace(':', ' ').replace('/', ' ').replace('*', ' ').replace('"', '\'');
        // Whatever is still outside the accepted set becomes a space, one per code point.
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            out.append(accepted(cp) ? (char) cp : ' ');
            i += Character.charCount(cp);
        }
        return out.toString().replaceAll(" {2,}", " ");
    }

    /** The characters the API has been seen to accept: a whitelist, because the refused set is not documented. */
    private static boolean accepted(int cp) {
        if (cp == '\n' || cp == '\r' || cp == '\t') {
            return true;
        }
        if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')) {
            return true;
        }
        return " .,()-%@&#'+".indexOf(cp) >= 0;
    }
}
