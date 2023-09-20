package zw.co.reikan.nanoloansweb;

public interface SsbService {
    SsbResponse process(SsbApprovalRequest loanRequest);
}
