package zw.co.reikan.loans;

import lombok.AllArgsConstructor;
import lombok.Data;
import zw.co.reikan.loans.core.loan.LoanDto;

import java.util.List;

@Data
@AllArgsConstructor
public class LoansWrapper {
    private List<LoanDto> loans;
}
