package zw.co.innbucks.loans.core.loan;

/**
 * Customer-facing loan SMS, one for each stage the applicant is told about (FR-SSB-016). Two rules
 * hold for every one:
 * <ul>
 *   <li>None of {@code ! : / ? " * ;} — the InnBucks SMS gateway refuses a body
 *       containing any of them (probed character by character for the fleet's
 *       SmsTextSanitizer), and a refused SMS is simply never delivered.</li>
 *   <li>No free text. A decline says it was declined and where to ask; the credit
 *       reviewer's comment and Ndasenda's reason are staff notes, kept on the loan.</li>
 * </ul>
 * Arguments are positional and the same for every caller: {@code %1$s} the loan
 * reference, {@code %2$s} the amount. Pinned by {@code SmsTemplatesTest}.
 */
public final class SmsMessages {
    // One per stage the applicant is told about (FR-SSB-016), sent through LoanNotificationService.
    public static final String RECEIVED_LOAN = "Your loan application with ref # %1$s has been received. We will send it to SSB for your salary deduction and let you know the outcome. Thank you for choosing Innbucks.";
    public static final String SENT_TO_SSB_LOAN = "Your loan application with ref # %1$s has been sent to SSB to confirm your salary deduction. You will be notified of the outcome shortly.";
    public static final String SSB_CONFIRMED_LOAN = "SSB has confirmed the salary deduction for your loan application with ref # %1$s. It is now being assessed and you will be notified of the outcome.";
    /** Credit returned the application for more information; it has not been declined. */
    public static final String RETURNED_LOAN = "Your loan application with ref # %1$s needs more information before a decision can be made. Innbucks or your agent will contact you.";
    public static final String RESUBMITTED_LOAN = "Thank you. The information for your loan application with ref # %1$s has been received and it is being assessed again.";
    public static final String APPROVED_LOAN = "Congratulations. Your loan application with ref %1$s has been approved. Your loan amount of %2$s will be disbursed to your account soon";
    public static final String REJECTED_LOAN = "We regret to inform you that your loan application with ref # %1$s has been declined. Please contact Innbucks for more information.";
    /** Approved, but the payout did not go through. It may yet be paid, so the message promises nothing either way. */
    public static final String PAYOUT_DELAYED_LOAN = "There is a delay in paying out your approved loan with ref # %1$s. Innbucks will contact you.";
    /** The application never reached SSB, so no deduction was set up. */
    public static final String NOT_COMPLETED_LOAN = "We could not complete your loan application with ref # %1$s. Please contact Innbucks for more information.";

    private SmsMessages() {
    }
}
