package zw.co.reikan.loans.core.loan;

import org.mapstruct.Mapper;
import zw.co.reikan.loans.core.merchant.MerchantMapper;

import java.util.List;

@Mapper(componentModel = "spring", uses = {MerchantMapper.class})
public interface LoanMapper {
    LoanDto fromLoan(Loan loan);

    List<LoanDto> fromLoans(List<Loan> loans);
}
