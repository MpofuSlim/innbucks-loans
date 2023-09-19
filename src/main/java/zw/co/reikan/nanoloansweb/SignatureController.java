package zw.co.reikan.nanoloansweb;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Controller
public class SignatureController {

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

    @PostMapping("/save-signature")
    public String saveSignature(@RequestBody String signatureData) {
        // Process the signature data as needed
        System.out.println("Received signature data: " + signatureData);

        // Return the view name or redirect to another page
        return "signature";
    }
}