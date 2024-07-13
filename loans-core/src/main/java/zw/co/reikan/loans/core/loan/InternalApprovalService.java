package zw.co.reikan.loans.core.loan;

public interface InternalApprovalService {
    InternalApprovalResponse approveLoan(InternalApprovalRequest request, Long id);
}
