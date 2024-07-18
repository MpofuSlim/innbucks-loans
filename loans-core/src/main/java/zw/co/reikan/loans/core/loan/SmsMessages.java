package zw.co.reikan.loans.core.loan;

public interface SmsMessages {
    String APPROVED_LOAN = "Congratulations! Your loan application with ref %1$s has been approved. Your loan amount of %2$s will be disbursed to your account soon";
    String REJECTED_LOAN = "We regret to inform you that your loan application with ref # %1$s has been declined. %3$s. Contact Innbucks for more Info";
    String PROCESSING_LOAN = "Your loan application with ref # %1$s has been received and is being processed. You will be notified of the outcome shortly. Thank you for choosing Innbucks!";
}
