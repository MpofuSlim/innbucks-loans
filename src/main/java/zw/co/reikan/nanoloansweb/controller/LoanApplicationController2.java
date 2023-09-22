package zw.co.reikan.nanoloansweb.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoanApplicationController2 {

    @GetMapping("/topup")
    public String showLoanApplicationPage() {
        return "29_topup";
    }
}