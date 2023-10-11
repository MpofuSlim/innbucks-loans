package zw.co.reikan.loans.core.loan;

import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface LoanMapper {
    LoanDto fromLoan(Loan loan);

    List<LoanDto> fromLoans(List<Loan> loans);
}
