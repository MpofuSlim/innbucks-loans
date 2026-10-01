package zw.co.innbucks.loans.core.staff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** A grade is the bank's own: a Paterson grade or a band, written one way however a spreadsheet typed it. */
class StaffGradesTest {

    @Test
    @DisplayName("upper case, runs of spaces as one, no space around a slash")
    void normalise() {
        assertThat(StaffGrades.normalise(" c4 ")).isEqualTo("C4");
        assertThat(StaffGrades.normalise("CLERK/ ASSISTANT/ AGENT")).isEqualTo("CLERK/ASSISTANT/AGENT");
        assertThat(StaffGrades.normalise("Clerk / Assistant / Agent")).isEqualTo("CLERK/ASSISTANT/AGENT");
        assertThat(StaffGrades.normalise("driver/\toffice   orderly")).isEqualTo("DRIVER/OFFICE ORDERLY");
        assertThat(StaffGrades.normalise("b4-u")).isEqualTo("B4-U");
        assertThat(StaffGrades.normalise(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"C4", " c4 ", "D-1", "EXCO", "HOD", "CLERK/ASSISTANT/AGENT", "DRIVER/ OFFICE ORDERLY",
            "OFFICE ORDERLY", "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"})
    @DisplayName("a Paterson grade or a band of at most 32 characters is a grade")
    void valid(String grade) {
        assertThat(StaffGrades.valid(grade)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "/C4", "C4/", "-C4", "C4%", "C4.1", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456"})
    @DisplayName("punctuation at either end, other symbols, or more than 32 characters is not")
    void invalid(String grade) {
        assertThat(StaffGrades.valid(grade)).isFalse();
        assertThat(StaffGrades.valid(null)).isFalse();
    }

    @Test
    @DisplayName("the length is counted as the grade is stored, not as it was typed")
    void lengthAsStored() {
        String typed = "CLERK  /  ASSISTANT  /  AGENT  /  OTHER";
        assertThat(typed.length()).isGreaterThan(StaffGrades.MAX_LENGTH);
        assertThat(StaffGrades.normalise(typed)).hasSizeLessThanOrEqualTo(StaffGrades.MAX_LENGTH);
        assertThat(StaffGrades.valid(typed)).isTrue();
    }
}
