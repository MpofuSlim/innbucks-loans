package zw.co.innbucks.loans.core.staff.notification;

/** Where the message to a member's phone stands. The in-app copy is stored whatever this says. */
public enum StaffNotificationOutboundStatus {
    /** Not attempted yet. */
    PENDING,
    /**
     * Claimed and being sent. One left here for more than a few minutes was being sent when the service stopped:
     * whether it went out is unknown, so it is never sent again.
     */
    SENDING,
    /** Accepted by the SMS API, or by the WhatsApp gateway after the SMS failed. */
    SENT,
    /** Every channel failed; the in-app copy is all the member has. */
    FAILED,
    /** Not sent, for the skip reason. */
    SKIPPED
}
