package zw.co.innbucks.loans.core.staff.notification;

/** What happened to one attempt to reach a member on one channel (FR-SGL-023). */
public enum StaffNotificationDispatchStatus {
    /** Put in the member's in-app inbox. */
    STORED,
    /** Accepted by the gateway. Whether it reached the handset is not reported back to us. */
    SENT,
    /** Refused by the gateway, or the gateway could not be reached; the reason is kept. */
    FAILED
}
