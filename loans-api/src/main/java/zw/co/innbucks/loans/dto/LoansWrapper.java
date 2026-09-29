package zw.co.innbucks.loans.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import zw.co.innbucks.loans.core.loan.LoanDto;

import java.util.List;

@Data
@AllArgsConstructor
public class LoansWrapper {
    private List<LoanDto> loans;
}
