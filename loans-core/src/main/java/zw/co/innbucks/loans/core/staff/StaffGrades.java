package zw.co.innbucks.loans.core.staff;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a grade is written everywhere. A grade is whatever the bank grades its staff by: a Paterson grade such as C4, or
 * a band such as MANAGER or CLERK/ASSISTANT/AGENT, which is what the bank's own staff list and limit matrix use. It is
 * stored upper case, with runs of spaces as one and no space around a slash, so {@code clerk/ assistant/ agent } and
 * {@code CLERK/ASSISTANT/AGENT} are one grade: spreadsheets type the same band both ways.
 */
public final class StaffGrades {

    /** The longest a grade may be once written the one way; the grade columns hold this many characters. */
    public static final int MAX_LENGTH = 32;

    /** Letters, digits, spaces, hyphens and slashes, starting and ending with a letter or digit. */
    public static final String PATTERN = "\\s*[A-Za-z0-9](?:[A-Za-z0-9\\s/-]*[A-Za-z0-9])?\\s*";
    public static final String MESSAGE = "Grade must be at most 32 letters, digits, spaces, hyphens or slashes, e.g. C4"
            + " or CLERK/ASSISTANT/AGENT";

    // Compiled once: a register upload normalises and checks a grade on every row.
    private static final Pattern VALID = Pattern.compile(PATTERN);
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Pattern SPACED_SLASH = Pattern.compile(" ?/ ?");

    private StaffGrades() {
    }

    public static String normalise(String grade) {
        return grade == null ? null
                : SPACED_SLASH.matcher(WHITESPACE_RUN.matcher(grade.strip()).replaceAll(" ")).replaceAll("/")
                        .toUpperCase(Locale.ROOT);
    }

    /** Whether a grade as typed is one: the characters {@link #PATTERN} allows, and at most {@link #MAX_LENGTH}. */
    public static boolean valid(String grade) {
        return grade != null && VALID.matcher(grade).matches() && normalise(grade).length() <= MAX_LENGTH;
    }
}
