package zw.co.reikan.loans.core.ndasenda;

import zw.co.reikan.loans.core.loan.LoanApprovalStatus;

public enum NdasendaDeductionStatus {

    SUCCESS(LoanApprovalStatus.APPROVED), FAILED(LoanApprovalStatus.REJECTED);

    private final LoanApprovalStatus approvalStatus;

    NdasendaDeductionStatus(LoanApprovalStatus approvalStatus) {
        this.approvalStatus = approvalStatus;
    }

    public LoanApprovalStatus getApprovalStatus() {
        return approvalStatus;
    }
}
