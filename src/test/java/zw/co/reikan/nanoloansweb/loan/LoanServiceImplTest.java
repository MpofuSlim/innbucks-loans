package zw.co.reikan.nanoloansweb.loan;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

@ExtendWith(MockitoExtension.class)
@Slf4j
class LoanServiceImplTest {

    @Mock
    private LoanRepository loanRepository;

    @InjectMocks
    private LoanServiceImpl loanService;


    @Test
    public void calculateGrossOfFees() {
        final LoanRequest loanRequest = LoanRequest.builder()
                .adminFeeRate(new BigDecimal("6"))
                .amount(new BigDecimal("265.96"))
                .tenor(12)
                .interestRate(new BigDecimal("7"))
                .commissionRate(new BigDecimal("3"))
                .build();
        final LoanDetails loanDetails = loanService.calculate(loanRequest);

        log.info("Loan Details: {}", loanDetails);
    }


    @Test
    public void calculateNetOfFees() {
        final LoanRequest loanRequest = LoanRequest.builder()
                .adminFeeRate(new BigDecimal("6"))
                .amount(new BigDecimal("250"))
                .tenor(12)
                .interestRate(new BigDecimal("7"))
                .commissionRate(new BigDecimal("3"))
                .type(LoanAmountType.NET_OF_FEES)
                .build();
        final LoanDetails loanDetails = loanService.calculate(loanRequest);

        log.info("Loan Details: {}", loanDetails);
    }
}