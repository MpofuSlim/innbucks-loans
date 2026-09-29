package zw.co.innbucks.loans.core.ndasenda;

import lombok.Builder;
import lombok.Data;
import zw.co.innbucks.loans.core.loan.LoanApprovalStatus;

import java.time.LocalDate;

@Builder
@Data
public class LoanApprovalResponse {
    private LoanApprovalStatus status;
    private String message;
    private String reference;
    private String batchNumber;
    private LocalDate startDate;
    private LocalDate endDate;
}
