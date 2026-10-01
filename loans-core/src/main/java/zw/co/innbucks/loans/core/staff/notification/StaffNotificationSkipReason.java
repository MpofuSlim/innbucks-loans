package zw.co.innbucks.loans.core.staff.notification;

/** Why a notification was not sent to the member's phone. Its in-app copy is kept either way. */
public enum StaffNotificationSkipReason {
    /** The member opted out of offer messages (FR-SGL-022). */
    OPTED_OUT,
    /** The member has ignored the configured number of offers in a row, and was messaged recently (FR-SGL-021). */
    FREQUENCY_CAP,
    /** The offer was no longer open by the time it came to be sent. */
    OFFER_CLOSED
}
