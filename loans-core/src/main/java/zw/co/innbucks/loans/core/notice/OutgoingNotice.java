package zw.co.innbucks.loans.core.notice;

/**
 * A message to send the applicant, captured when the notice is raised: the loan may have moved on by the time
 * it is sent, and the applicant is told what was true then.
 *
 * @param gatewayReference our reference for it at the SMS gateway, recorded with it
 */
public record OutgoingNotice(Long loanId, LoanNotice notice, String recipient, String message,
                             String gatewayReference) {

    /** Everything but the recipient and the text. */
    @Override
    public String toString() {
        return "OutgoingNotice[loanId=" + loanId + ", notice=" + notice + ", gatewayReference=" + gatewayReference + "]";
    }
}
