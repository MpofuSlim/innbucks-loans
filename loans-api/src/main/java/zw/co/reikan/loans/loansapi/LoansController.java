package zw.co.reikan.loans.loansapi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.loans.core.loan.LoanDto;
import zw.co.reikan.loans.core.loan.LoanService;

import javax.websocket.server.PathParam;
import java.time.LocalDate;
import java.util.List;

@RestController
@Slf4j
public class LoansController {

    @Autowired
    private LoanService loanService;

    @GetMapping("/loans")
    public LoansWrapper getLoans(@PathParam("endDate") LocalDate startDate,
                                 @PathParam("endDate") LocalDate endDate) {
        final List<LoanDto> loans = loanService.findByDateCreated(startDate, endDate);
        return new LoansWrapper(loans);
    }
}
