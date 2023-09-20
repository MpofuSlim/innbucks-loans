package zw.co.reikan.nanoloansweb;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class LoanApplicationController {

    @Autowired
    private LoanServiceImpl loanService;

    @GetMapping("/apply")
    public String showApplyPage() {
        return "apply";
    }

    @GetMapping("/loan-application")
    public String showLoanApplicationPage() {
        return "loan-application";
    }


    @GetMapping("/signature")
    public String showSignaturePage() {
        return "signature";
    }

    @PostMapping(value = "/save-signature", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String saveSignature(@ModelAttribute LoanRequest loanRequest) {
        final LoanResponse loanResponse = loanService.requestLoan(loanRequest);
        return "loan-application";
    }
}