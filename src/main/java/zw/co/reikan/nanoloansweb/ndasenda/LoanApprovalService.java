package zw.co.reikan.nanoloansweb.ndasenda;

public interface LoanApprovalService {
    SsbResponse process(LoanApprovalRequest loanRequest);
}
