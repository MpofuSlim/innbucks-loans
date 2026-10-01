package zw.co.innbucks.loans.core.staff;

import java.util.Locale;

/** How a Paterson grade is written everywhere: trimmed and upper case, so {@code c4 } and {@code C4} are one grade. */
public final class StaffGrades {

    /** 1 to 16 letters, digits or hyphens, e.g. C4, B4U or D-1. */
    public static final String PATTERN = "\\s*[A-Za-z0-9][A-Za-z0-9-]{0,15}\\s*";
    public static final String MESSAGE = "Grade must be 1 to 16 letters, digits or hyphens, e.g. C4";

    private StaffGrades() {
    }

    public static String normalise(String grade) {
        return grade == null ? null : grade.strip().toUpperCase(Locale.ROOT);
    }
}
