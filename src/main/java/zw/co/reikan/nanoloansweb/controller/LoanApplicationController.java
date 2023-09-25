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

import javax.servlet.http.HttpSession;

@Controller
public class LoanApplicationController {

    @Autowired
    private LoanServiceImpl loanService;

    @GetMapping("/apply")
    public String showLoanApplicationPage(@RequestParam("mobileNumber") String mobileNumber,
                                          @RequestParam("fname") String firstName,
                                          @RequestParam("lname") String lastName,
                                          Model model, HttpSession session) {
        session.setAttribute("mobileNumber", mobileNumber);
        session.setAttribute("fname", firstName);
        session.setAttribute("lname", lastName);
        return "apply";
    }

    @PostMapping(value = "/apply", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String saveSignature(@ModelAttribute LoanRequest loanRequest, Model model) {
        final LoanResponse loanResponse = loanService.requestLoan(loanRequest);
        model.addAttribute("internalReference", loanResponse.getInternalReference());
        return loanResponse.getLoanApprovalStatus() == LoanApprovalStatus.REJECTED ? "fail" : "success";
    }
}