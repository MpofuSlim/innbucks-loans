package zw.co.innbucks.loans.core.loan;

import org.mapstruct.Mapper;
import zw.co.innbucks.loans.core.merchant.MerchantMapper;

import java.util.List;

@Mapper(componentModel = "spring", uses = {MerchantMapper.class})
public interface LoanMapper {
    LoanDto fromLoan(Loan loan);

    List<LoanDto> fromLoans(List<Loan> loans);
}
