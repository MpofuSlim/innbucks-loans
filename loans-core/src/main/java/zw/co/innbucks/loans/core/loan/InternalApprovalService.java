package zw.co.innbucks.loans.core.loan;

public interface InternalApprovalService {
    InternalApprovalResponse approveLoan(InternalApprovalRequest request, Long id);
}
