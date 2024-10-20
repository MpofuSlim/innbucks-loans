package zw.co.reikan.loans.core.loan;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import zw.co.reikan.loans.core.disbursements.LoanDisbursementStatus;

import java.time.LocalDate;

@Data
@SuperBuilder
@AllArgsConstructor
@NoArgsConstructor
public class FindLoansRequest {
    @Schema(description = "format yyyy-MM-dd")
    private LocalDate fromDate;
    @Schema(description = "format yyyy-MM-dd")
    private LocalDate toDate;
    private LoanApprovalStatus approvalStatus;
    private LoanDisbursementStatus disbursementStatus;
    private InternalApprovalStatus internalApprovalStatus;
}
