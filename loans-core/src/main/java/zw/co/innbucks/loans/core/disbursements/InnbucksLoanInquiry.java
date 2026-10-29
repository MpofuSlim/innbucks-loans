package zw.co.innbucks.loans.core.disbursements;

/**
 * The bank's inquiry for one booking ({@code GET /bank/api/loan/inquiry/{participantReference}}): read-only, so
 * whatever holds it can ask about a payout and can never send one. The Staff Grocery Loan's payout status check
 * depends on this and on nothing that books.
 */
public interface InnbucksLoanInquiry {

    /**
     * What the bank says it holds under {@code participantReference}. Never throws: a failed call, an answer that is
     * not the bank's envelope, or an envelope that says neither "found" nor "not found" is {@link InquiryAnswer.Kind#UNANSWERED}.
     */
    InquiryAnswer inquire(String participantReference);
}
