package zw.co.innbucks.loans.core.staff;

/** What a grade change does to a grade in the matrix (FR-SGL-010). */
public enum StaffGradeChangeAction {
    /** Takes the grade out of the matrix: its limits stop applying and the register refuses it. */
    RETIRE,
    /** Gives the grade a new name: its limits, its staff and their limit overrides move to the new name. */
    RENAME
}
