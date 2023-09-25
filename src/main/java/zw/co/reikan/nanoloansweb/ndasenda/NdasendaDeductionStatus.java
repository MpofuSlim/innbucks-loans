package zw.co.reikan.nanoloansweb.ndasenda;

import zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus;

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
