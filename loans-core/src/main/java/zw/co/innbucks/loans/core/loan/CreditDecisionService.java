package zw.co.innbucks.loans.core.loan;

import java.util.List;

/**
 * Credit's assessment of a loan SSB has accepted (FR-SSB-015, applying FR-PBL-027, 028, 029 and 032):
 * approve it for booking, reject it, or return it to the originator for more information; or, when it
 * is above the officer's approval limit, refer it to a higher credit authority. Every action is kept
 * in the loan's credit decision log.
 */
public interface CreditDecisionService {

    /**
     * Records the decision and returns the loan as it now stands.
     *
     * @throws zw.co.innbucks.loans.core.exception.NotFoundException no such loan
     * @throws org.springframework.security.access.AccessDeniedException the caller originated, resubmitted
     *                                                                   or is a party to the loan and is approving it,
     *                                                                   or it is above their approval limit
     */
    LoanResponse decide(Long loanId, CreditDecisionRequest request);

    /**
     * Refers a loan above the caller's approval limit to the credit authority that covers it, with the caller's
     * recommendation, and emails whoever may approve it. The loan stays in the credit queue for them; the caller's
     * assignment of it, if any, is released.
     *
     * @throws zw.co.innbucks.loans.core.exception.NotFoundException    no such loan
     * @throws zw.co.innbucks.loans.core.exception.ConflictException    no approval limits are set up, the loan is
     *                                                                  within the caller's limit, or it is assigned to
     *                                                                  someone else at an EXCLUSIVE credit decision
     * @throws org.springframework.security.access.AccessDeniedException the caller originated, resubmitted or is a
     *                                                                   party to the loan and recommends approving it
     */
    CreditDecisionResponse refer(Long loanId, CreditReferralRequest request);

    /**
     * Answers a return: the loan goes back to PENDING in the credit queue, with the answer logged.
     *
     * @throws zw.co.innbucks.loans.core.exception.NotFoundException no such loan, or not one the caller may read
     */
    LoanResponse resubmit(Long loanId, CreditResubmissionRequest request, LoanReadScope scope);

    /** The loan's credit decision log, oldest first. */
    List<CreditDecisionResponse> history(Long loanId);

    /** The reason codes that can be chosen now, for one decision or (null) for all of them. */
    List<CreditReasonCodeResponse> reasonCodes(InternalApprovalStatus decision);
}
