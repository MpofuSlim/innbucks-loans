package zw.co.reikan.nanoloansweb.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import zw.co.reikan.nanoloansweb.loan.LoanDetails;
import zw.co.reikan.nanoloansweb.loan.LoanRequest;
import zw.co.reikan.nanoloansweb.loan.LoanService;

import java.math.BigDecimal;

@RestController
@Slf4j
public class LoanApiResource {


    @Autowired
    private LoanService loanService;

    @PostMapping("/loan/calculate")
    public LoanDetails calculate(@RequestBody LoanRequest loanRequest) {
        log.info("Processing loan request: {}", loanRequest);
        loanRequest.setInterestRate(new BigDecimal(7));
        loanRequest.setAdminFeeRate(new BigDecimal(6));
        loanRequest.setCommissionRate(new BigDecimal(3));
        return loanService.calculate(loanRequest);
    }
}
