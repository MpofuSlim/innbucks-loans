package zw.co.reikan.nanoloansweb.loan;

import java.util.List;

public enum LoanApprovalStatus {

    NEW, PROCESSING, APPROVED, REJECTED;

    public static List<LoanApprovalStatus> activeLoanStatuses = List.of(NEW, PROCESSING, APPROVED);

}
