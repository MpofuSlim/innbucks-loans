package zw.co.innbucks.loans.core.staff.notification;

/** Where a staff notification was put: the member's in-app inbox, or their phone (FR-SGL-019, FR-SGL-023). */
public enum StaffNotificationChannel {
    /** Stored here, as the member's in-app inbox. */
    IN_APP,
    /** Through the InnBucks notification API. */
    SMS,
    /** Through the WhatsApp gateway, when the SMS failed. */
    WHATSAPP
}
