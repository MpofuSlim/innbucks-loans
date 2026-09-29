package zw.co.innbucks.loans.core.ndasenda;

public interface LoanApprovalService {
    /**
     * Lodges the loan's payroll deduction. Returns only when it was accepted; every other outcome is a
     * {@link LodgementException} saying whether the deduction may have been lodged. The caller treats
     * anything else that escapes as an unknown outcome: held, never sent again.
     */
    LoanApprovalResponse requestApproval(LoanApprovalRequest loanRequest);
}
