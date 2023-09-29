package zw.co.reikan.nanoloansweb.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import zw.co.reikan.nanoloansweb.LoanResponse;
import zw.co.reikan.nanoloansweb.loan.LoanApprovalStatus;
import zw.co.reikan.nanoloansweb.loan.LoanRequest;
import zw.co.reikan.nanoloansweb.loan.LoanServiceImpl;
import zw.co.reikan.nanoloansweb.parameter.ParameterService;

import javax.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.util.Map;

import static zw.co.reikan.nanoloansweb.loan.Constants.ADMI_FEE_RATE;
import static zw.co.reikan.nanoloansweb.loan.Constants.COMMISSION_RATE;
import static zw.co.reikan.nanoloansweb.loan.Constants.DEFAULT_LOAN_AMOUNT;
import static zw.co.reikan.nanoloansweb.loan.Constants.DEFAULT_LOAN_TENOR;
import static zw.co.reikan.nanoloansweb.loan.Constants.MAXIMUM_LOAN_AMOUNT;
import static zw.co.reikan.nanoloansweb.loan.Constants.MAXIMUM_LOAN_TENOR;
import static zw.co.reikan.nanoloansweb.loan.Constants.MINIMUM_LOAN_AMOUNT;
import static zw.co.reikan.nanoloansweb.loan.Constants.MINIMUM_LOAN_TENOR;
import static zw.co.reikan.nanoloansweb.loan.Constants.MONTHLY_INTEREST_RATE;

@Controller
public class LoanApplicationController {

    @Autowired
    private LoanServiceImpl loanService;

    @Autowired
    private ParameterService parameterService;

    @GetMapping("/apply")
    public String showLoanApplicationPage(@RequestParam("mobileNumber") String mobileNumber,
                                          @RequestParam("fname") String firstName,
                                          @RequestParam("lname") String lastName,
                                          @RequestParam("idNumber") String nationalId,
                                          Model model, HttpSession session) {


        final Map<String, String> params = parameterService.getParameterValues(
                COMMISSION_RATE,
                ADMI_FEE_RATE,
                MONTHLY_INTEREST_RATE,
                MINIMUM_LOAN_TENOR,
                MAXIMUM_LOAN_TENOR,
                MINIMUM_LOAN_AMOUNT,
                MAXIMUM_LOAN_AMOUNT,
                DEFAULT_LOAN_AMOUNT,
                DEFAULT_LOAN_TENOR);

        session.setAttribute("mobileNumber", mobileNumber);
        session.setAttribute("fname", firstName.toUpperCase());
        session.setAttribute("lname", lastName.toUpperCase());
        session.setAttribute("nationalId", nationalId);

        session.setAttribute(COMMISSION_RATE, new BigDecimal(params.get(COMMISSION_RATE)));
        session.setAttribute(ADMI_FEE_RATE, new BigDecimal(params.get(ADMI_FEE_RATE)));
        session.setAttribute(MONTHLY_INTEREST_RATE, new BigDecimal(params.get(MONTHLY_INTEREST_RATE)));
        session.setAttribute(MINIMUM_LOAN_TENOR, new BigDecimal(params.get(MINIMUM_LOAN_TENOR)));
        session.setAttribute(MAXIMUM_LOAN_TENOR, new BigDecimal(params.get(MAXIMUM_LOAN_TENOR)));
        session.setAttribute(MINIMUM_LOAN_AMOUNT, new BigDecimal(params.get(MINIMUM_LOAN_AMOUNT)));
        session.setAttribute(MAXIMUM_LOAN_AMOUNT, new BigDecimal(params.get(MAXIMUM_LOAN_AMOUNT)));
        session.setAttribute(DEFAULT_LOAN_AMOUNT, new BigDecimal(params.get(DEFAULT_LOAN_AMOUNT)));
        session.setAttribute(DEFAULT_LOAN_TENOR, new BigDecimal(params.get(DEFAULT_LOAN_TENOR)));

        return "apply";
    }

    @PostMapping(value = "/apply", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String saveSignature(@ModelAttribute LoanRequest loanRequest, Model model, HttpSession session) {

        final String mobileNumber = String.valueOf(session.getAttribute("mobileNumber"));
        loanRequest.setMobileNumber(mobileNumber);
        final LoanResponse loanResponse = loanService.requestLoan(loanRequest);
        model.addAttribute("internalReference", loanResponse.getInternalReference());
        return loanResponse.getLoanApprovalStatus() == LoanApprovalStatus.REJECTED ? "fail" : "success";
    }
}