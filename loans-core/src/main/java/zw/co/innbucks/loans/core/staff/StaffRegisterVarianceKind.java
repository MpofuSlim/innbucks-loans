package zw.co.innbucks.loans.core.staff;

/** What a reconciliation found for one employee or payroll row (FR-SGL-008), most urgent first. */
public enum StaffRegisterVarianceKind {
    /** On the register as still employed, but the payroll's status says they have left: may still be able to borrow. */
    LEFT_ON_PAYROLL,
    /** On the register as still employed, but not on the payroll: probably left, and may still be able to borrow. */
    NOT_ON_PAYROLL,
    /** On both, with fields that disagree. */
    DIFFERENT,
    /** On the payroll as still employed, but not on the register: probably joined, and not yet added. */
    NOT_ON_REGISTER,
    /** One employee number on more than one payroll row; not compared, since which row is right is unknown. */
    DUPLICATE_ON_PAYROLL,
    /** A payroll row with no usable employee number. */
    UNREADABLE
}
