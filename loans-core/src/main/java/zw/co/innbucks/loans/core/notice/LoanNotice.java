package zw.co.innbucks.loans.core.notice;

import zw.co.innbucks.loans.core.loan.Loan;
import zw.co.innbucks.loans.core.loan.LoanStage;
import zw.co.innbucks.loans.core.loan.SmsMessages;

/**
 * Something the applicant is told about their application (FR-SSB-016): each stage it reaches, and each
 * decision on it. Declines from SSB, Credit and a confirmed payslip review are one notice, worded the same,
 * so the applicant learns the outcome and where to ask, never why.
 */
public enum LoanNotice {

    RECEIVED(LoanStage.RECEIVED, SmsMessages.RECEIVED_LOAN),
    SENT_TO_SSB(LoanStage.WITH_SSB, SmsMessages.SENT_TO_SSB_LOAN),
    SSB_CONFIRMED(LoanStage.WITH_CREDIT, SmsMessages.SSB_CONFIRMED_LOAN),
    MORE_INFORMATION_NEEDED(LoanStage.MORE_INFORMATION_NEEDED, SmsMessages.RETURNED_LOAN),
    RESUBMITTED(LoanStage.WITH_CREDIT, SmsMessages.RESUBMITTED_LOAN),
    APPROVED(LoanStage.APPROVED, SmsMessages.APPROVED_LOAN),
    DECLINED(LoanStage.DECLINED, SmsMessages.REJECTED_LOAN),
    /** Worded by the payout, which names where the money went; see DisbursementService#disbursementSms. */
    PAID(LoanStage.PAID, null),
    PAYOUT_DELAYED(LoanStage.PAYOUT_DELAYED, SmsMessages.PAYOUT_DELAYED_LOAN),
    NOT_COMPLETED(LoanStage.NOT_COMPLETED, SmsMessages.NOT_COMPLETED_LOAN);

    private final LoanStage stage;
    private final String template;

    LoanNotice(LoanStage stage, String template) {
        this.stage = stage;
        this.template = template;
    }

    /** The stage the applicant is told the application has reached. */
    public LoanStage stage() {
        return stage;
    }

    /**
     * The message for this loan: its reference and amount, nothing else.
     *
     * @throws IllegalStateException for a notice worded by its sender ({@link #PAID})
     */
    public String textFor(Loan loan) {
        if (template == null) {
            throw new IllegalStateException(this + " is worded by its sender");
        }
        return String.format(template, loan.getReference(), loan.getDisbursedAmount());
    }
}
