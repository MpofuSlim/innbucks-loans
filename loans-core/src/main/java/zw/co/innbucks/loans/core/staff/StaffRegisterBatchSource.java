package zw.co.innbucks.loans.core.staff;

/** How a batch reached the staff register (FR-SGL-002). */
public enum StaffRegisterBatchSource {
    /** A file of staff records. */
    UPLOAD,
    /** One record added or changed on the admin screen. */
    MANUAL
}
