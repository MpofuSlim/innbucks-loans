package zw.co.innbucks.loans.core.disbursements;

import java.math.BigDecimal;

/**
 * The bank's answer to one inquiry ({@link InnbucksLoanInquiry}), read leniently: every field is what the bank sent,
 * or null when it sent nothing readable there. Nothing here is decided beyond the kind of answer; what it means for a
 * loan is the caller's.
 *
 * @param kind            FOUND (responseCode 000 in a 2xx), NOT_FOUND (responseCode 004, in a 2xx or a 404) or
 *                        UNANSWERED (anything else, a bare 404 included)
 * @param responseCode    the envelope's {@code responseCode}
 * @param bankStatus      {@code additionalData.loanDetails.status}, e.g. SETTLED
 * @param bankLoanAccount {@code loanDetails.loanAccountNumber}, the bank's own loan account; null when blank or longer
 *                        than 64 characters
 * @param echoedReference {@code loanDetails.participantReference}, else the envelope's {@code participantReference}
 * @param amount          {@code loanDetails.amount}, else the envelope's {@code amount}, as the bank wrote it (cents
 *                        or major units: it has not said which)
 * @param interestRate    {@code loanDetails.interestRate}
 * @param echoedMsisdn    {@code loanDetails.msisdn}: the borrower's phone, which is also the wallet a wallet payout
 *                        pays
 * @param echoedSettlementAccount {@code loanDetails.settlementAccount}: the account an account payout pays. The two
 *                        are kept apart: which of them names where the money went depends on the payout's destination,
 *                        so one is never read in place of the other
 * @param raw             the answer, redacted and cut to {@link BankAnswers#MAX_CHARS}, or why there was none
 * @param httpStatus      the HTTP status, after the one replay a 401 gets; 0 when the bank did not answer
 */
public record InquiryAnswer(Kind kind, String responseCode, String bankStatus, String bankLoanAccount,
                            String echoedReference, BigDecimal amount, BigDecimal interestRate, String echoedMsisdn,
                            String echoedSettlementAccount, String raw, int httpStatus) {

    /** responseCode 000: the bank holds a loan under the reference. */
    public static final String FOUND_CODE = "000";
    /** responseCode 004: the bank holds none. */
    public static final String NOT_FOUND_CODE = "004";

    public enum Kind {
        /** The bank holds a loan under the reference (responseCode 000). */
        FOUND,
        /** The bank says it holds none (responseCode 004 in its own envelope). */
        NOT_FOUND,
        /** Nothing can be concluded: no answer, not the bank's envelope, or neither of the codes above. */
        UNANSWERED
    }

    /** No answer worth reading: {@code why} is kept as its raw text. */
    public static InquiryAnswer unanswered(int httpStatus, String responseCode, String why) {
        return new InquiryAnswer(Kind.UNANSWERED, responseCode, null, null, null, null, null, null, null, why,
                httpStatus);
    }

    /**
     * Whether this says the bank, or the way to it, is down rather than anything about this loan: no answer at all (a
     * failed login, a timeout, a connection refused), a 5xx, or a refusal of the call itself (401 after a fresh login,
     * 407, 408, 429). Every other inquiry is likely to end the same way, so a run stops here. Any other answer, even
     * one with nothing to go on (a bare 404, an HTML page, an unknown code), is the bank's about this one loan.
     */
    public boolean bankUnreachable() {
        if (kind != Kind.UNANSWERED) {
            return false;
        }
        return httpStatus == 0 || httpStatus >= 500 || httpStatus == 401 || httpStatus == 407 || httpStatus == 408
                || httpStatus == 429;
    }

    /** Names the kind, the codes and the bank's status; never the answer, the account or the amount. */
    @Override
    public String toString() {
        return "InquiryAnswer[kind=" + kind + ", httpStatus=" + httpStatus + ", responseCode=" + responseCode
                + ", bankStatus=" + bankStatus + "]";
    }
}
