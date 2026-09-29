package zw.co.innbucks.loans.core.loan;

import java.util.List;

public enum LoanApprovalStatus {

    NEW, PROCESSING, APPROVED, REJECTED, PAID, FAILED;

    public static List<LoanApprovalStatus> activeLoanStatuses = List.of(NEW, PROCESSING, APPROVED);

}
