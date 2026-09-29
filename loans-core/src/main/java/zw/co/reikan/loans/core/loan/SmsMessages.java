package zw.co.reikan.loans.core.loan;

/**
 * Customer-facing loan-decision SMS, shared by the credit sign-off, the Ndasenda
 * response processing and the SSB submission job. Two rules hold for every one:
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
public interface SmsMessages {
    String APPROVED_LOAN = "Congratulations. Your loan application with ref %1$s has been approved. Your loan amount of %2$s will be disbursed to your account soon";
    String REJECTED_LOAN = "We regret to inform you that your loan application with ref # %1$s has been declined. Please contact Innbucks for more information.";
    String PROCESSING_LOAN = "Your loan application with ref # %1$s has been received and is being processed. You will be notified of the outcome shortly. Thank you for choosing Innbucks.";
}
