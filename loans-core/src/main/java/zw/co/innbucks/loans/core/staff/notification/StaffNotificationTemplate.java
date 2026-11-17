package zw.co.innbucks.loans.core.staff.notification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The wording of each staff notification, with its version (FR-SGL-023 logs the template and version of every
 * dispatch). The same text goes in-app, by SMS and by WhatsApp. Each is written to pass the notification API unchanged
 * (no {@code ! : / ? " * ;}) and to fit one 160-character SMS, which {@code StaffNotificationTemplateTest} pins.
 * Changing a wording means raising its version, so the log says which wording each member was sent.
 */
public enum StaffNotificationTemplate {

    /** A weekly run made the member an offer, and they held none still open. */
    OFFER_NEW(1, "Your Staff Grocery Loan offer", "InnBucks Staff Grocery Loan. You have a new offer of up to USD %s,"
            + " open until %s. Log in to the InnBucks app to accept it."),
    /** A weekly run replaced an offer the member still held with a new one. */
    OFFER_REFRESHED(1, "Your Staff Grocery Loan offer is renewed", "InnBucks Staff Grocery Loan. Your offer is"
            + " renewed. You can borrow up to USD %s until %s. Log in to the InnBucks app to accept it."),
    /** The one-off product launch announcement to the whole register (FR-SGL-020). */
    LAUNCH(1, "Introducing the Staff Grocery Loan", "InnBucks has launched the Staff Grocery Loan for staff. Log in to"
            + " the InnBucks app to find out more."),
    /** The bank paid a cash loan into the borrower's own account: how much, where, when it is repaid. */
    LOAN_PAID_OUT(1, "Your Staff Grocery Loan is paid", "InnBucks paid your Staff Grocery Loan of %s %s into your %s"
            + " ending %s. Repaid from your salary on %s. Ref %s"),
    /** A cash loan was cancelled before anything was paid: nothing is owed. */
    LOAN_NOT_PAID_OUT(1, "Your Staff Grocery Loan was not paid", "InnBucks did not pay out your Staff Grocery Loan %s,"
            + " so it is cancelled and nothing is owed. Log in to the InnBucks app for details.");

    /** The API refuses a colon, so a time is written {@code 08.00}, as the rest of the fleet writes it. */
    private static final DateTimeFormatter UNTIL = DateTimeFormatter.ofPattern("HH.mm 'on' d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private final int version;
    private final String title;
    private final String text;

    StaffNotificationTemplate(int version, String title, String text) {
        this.version = version;
        this.title = title;
        this.text = text;
    }

    public int version() {
        return version;
    }

    public String title() {
        return title;
    }

    /** Whether it is about an offer: those are the ones the frequency cap and the offer's own state apply to. */
    public boolean aboutAnOffer() {
        return this == OFFER_NEW || this == OFFER_REFRESHED;
    }

    /**
     * Whether it is about the member's own loan: a service message about their money, sent whatever their offer
     * message preference and the frequency cap, and naming no offer.
     */
    public boolean aboutALoan() {
        return this == LOAN_PAID_OUT || this == LOAN_NOT_PAID_OUT;
    }

    /**
     * The text of an offer notification.
     *
     * @param openUntil when the offer lapses, on the market's clock
     */
    public String offerText(BigDecimal amount, OffsetDateTime openUntil) {
        if (!aboutAnOffer()) {
            throw new IllegalStateException(this + " is not about an offer");
        }
        return String.format(Locale.ROOT, text, amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                UNTIL.format(openUntil));
    }

    /**
     * The text of LOAN_PAID_OUT.
     *
     * @param destination how the account paid is named: "InnBucks wallet"
     * @param last4       its last four digits
     * @param repaidOn    the market day it is collected from salary
     */
    public String paidOutText(String currency, BigDecimal amount, String destination, String last4,
                              LocalDate repaidOn, String reference) {
        if (this != LOAN_PAID_OUT) {
            throw new IllegalStateException(this + " is not about a loan paid out");
        }
        return String.format(Locale.ROOT, text, currency, amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                destination, last4, DAY.format(repaidOn), reference);
    }

    /** The text of LOAN_NOT_PAID_OUT. */
    public String notPaidOutText(String reference) {
        if (this != LOAN_NOT_PAID_OUT) {
            throw new IllegalStateException(this + " is not about a loan not paid out");
        }
        return String.format(Locale.ROOT, text, reference);
    }

    /** The text of a notification that names nothing about the member. */
    public String text() {
        if (aboutAnOffer()) {
            throw new IllegalStateException(this + " is worded for an offer");
        }
        if (aboutALoan()) {
            throw new IllegalStateException(this + " is worded for a loan");
        }
        return text;
    }
}
