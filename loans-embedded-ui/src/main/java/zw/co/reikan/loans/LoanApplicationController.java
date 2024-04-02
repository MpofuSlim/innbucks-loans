package zw.co.reikan.loans;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import zw.co.reikan.loans.core.CryptoHelper;
import zw.co.reikan.loans.core.EncryptionUtils;
import zw.co.reikan.loans.core.LoanResponse;
import zw.co.reikan.loans.core.Utils;
import zw.co.reikan.loans.core.loan.*;
import zw.co.reikan.loans.core.parameter.ParameterService;

import javax.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

import static zw.co.reikan.loans.core.loan.Constants.*;


@Controller
@Slf4j
public class LoanApplicationController {

    private final static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-d");
    @Autowired
    private LoanServiceImpl loanService;

    @Autowired
    private ParameterService parameterService;

    @Value("${encryption-key}")
    private String decryptionKey;

    @Value("${encryption-key-v2}")
    private String _16BitDescryptionKey;

    @GetMapping("/loan")
    public String showLoanApplicationPage(@RequestParam("payload") String payload,
                                          @RequestParam(value = "version", defaultValue = "0") String version,
                                          Model model, HttpSession session) throws Exception {

        log.info("Loading application page: version: {}, payload: {}", version, payload);

        final String formattedPayload = payload.replaceAll("\n", "");
        String decrypt = "0".equals(version) ? EncryptionUtils.decrypt(formattedPayload, decryptionKey) :
                CryptoHelper.decrypt(formattedPayload, _16BitDescryptionKey);
        return decodeDataAndPopulateModel(payload, model, session, decrypt);
    }

    private String decodeDataAndPopulateModel(String payload, Model model, HttpSession session, String decrypt) {
        final String[] data = decrypt.split("\\|");
        String firstName = data[0];
        String lastName = data[1];
        String nationalId = Utils.trimSpecialCharacters(data[2]);
        String dob = data[3];
        String mobileNumber = data[4];

        final Optional<Loan> latestActiveLoan = loanService.findLatestActiveLoanByNationalId(nationalId);

        if (latestActiveLoan.isPresent()) {
            final Loan loan = latestActiveLoan.get();
            model.addAttribute("loan", loan);
            return "loan-details";
        }

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
        session.setAttribute("dob", dob);

        session.setAttribute(COMMISSION_RATE, new BigDecimal(params.get(COMMISSION_RATE)));
        session.setAttribute(ADMI_FEE_RATE, new BigDecimal(params.get(ADMI_FEE_RATE)));
        session.setAttribute(MONTHLY_INTEREST_RATE, new BigDecimal(params.get(MONTHLY_INTEREST_RATE)));
        session.setAttribute(MINIMUM_LOAN_TENOR, new BigDecimal(params.get(MINIMUM_LOAN_TENOR)));
        session.setAttribute(MAXIMUM_LOAN_TENOR, new BigDecimal(params.get(MAXIMUM_LOAN_TENOR)));
        session.setAttribute(MINIMUM_LOAN_AMOUNT, new BigDecimal(params.get(MINIMUM_LOAN_AMOUNT)));
        session.setAttribute(MAXIMUM_LOAN_AMOUNT, new BigDecimal(params.get(MAXIMUM_LOAN_AMOUNT)));
        session.setAttribute(DEFAULT_LOAN_AMOUNT, new BigDecimal(params.get(DEFAULT_LOAN_AMOUNT)));
        session.setAttribute(DEFAULT_LOAN_TENOR, new BigDecimal(params.get(DEFAULT_LOAN_TENOR)));
        session.setAttribute(PAYLOAD, payload);

        return "apply";
    }

    @PostMapping(value = "/apply", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String apply(@ModelAttribute LoanRequest loanRequest, Model model, HttpSession session) {
        loanRequest.setMobileNumber(String.valueOf(session.getAttribute("mobileNumber")));
        loanRequest.setFname(String.valueOf(session.getAttribute("fname")));
        loanRequest.setLname(String.valueOf(session.getAttribute("lname")));
        loanRequest.setNationalId(String.valueOf(session.getAttribute("nationalId")));
        LocalDate dateOfBirth = LocalDate.parse(String.valueOf(session.getAttribute("dob")), formatter);

        loanRequest.setMerchant(Merchant.INNBUCKS);

        loanRequest.setDateOfBirth(dateOfBirth);

        final EmploymentDetail employmentDetail = new EmploymentDetail();
        employmentDetail.setEmployerName("GOVERNMENT");
        employmentDetail.setGrossSalary(loanRequest.getGrossSalary());
        employmentDetail.setNetSalary(loanRequest.getNetSalary());
        loanRequest.setEmploymentDetail(employmentDetail);

        final NextOfKin nextOfKin = new NextOfKin();
        nextOfKin.setFirstName(capitalise(loanRequest.getNextOfKinName()));
        nextOfKin.setLastName(capitalise(loanRequest.getNextOfKinSurname()));
        nextOfKin.setNationalId(capitalise(loanRequest.getNextOfKinIdNumber()));
        loanRequest.setNextOfKin(nextOfKin);

        final LoanResponse loanResponse = loanService.requestLoan(loanRequest);

        model.addAttribute("internalReference", loanResponse.getInternalReference());
        return loanResponse.getLoanApprovalStatus() == LoanApprovalStatus.REJECTED ? "fail" : "success";
    }


    public String capitalise(String text) {
        if (StringUtils.hasText(text)) {
            return text.toUpperCase();
        }
        return "";
    }

    @GetMapping("/loanDetails")
    public String loanDetails() {
        return "loan-details";
    }
}