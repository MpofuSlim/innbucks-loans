package zw.co.reikan.loans;

import jakarta.servlet.http.HttpSession;
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
import zw.co.reikan.loans.core.merchant.Merchant;
import zw.co.reikan.loans.core.parameter.ParameterService;

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

    @Value("${bulkit.loans.channel-id}")
    private String channelId;

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

        log.info("Decrypted string: {}", decrypt);

        final String[] data = decrypt.split("\\|");
        String firstName = data[0];
        String lastName = data[1];
        String nationalId = Utils.trimSpecialCharacters(data[2]);
        String dob = data[3];
        String mobileNumber = data[4];

        String address = data[5];
        String suburb = data[6];
        String town = data[7];

        // THOMAS|NYAGWAYA|75354973D75|1990-09-23|263772819815|520 Patrick Close|test|Harare Urban
        //address|suburb|town

        final Optional<Loan> latestActiveLoan = loanService.findLatestActiveLoanByNationalId(nationalId);

        if (latestActiveLoan.isPresent()) {
            final Loan loan = latestActiveLoan.get();

            // Add loan details to model
            addLoanDetailsToModel(model, loan);

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

        // Store in session
        session.setAttribute("mobileNumber", mobileNumber);
        session.setAttribute("fname", firstName.toUpperCase());
        session.setAttribute("lname", lastName.toUpperCase());
        session.setAttribute("nationalId", nationalId);
        session.setAttribute("dob", dob);

        session.setAttribute("address", address);
        session.setAttribute("suburb", suburb);
        session.setAttribute("town", town);

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

        // Also add to model for Thymeleaf
        model.addAttribute("fname", firstName.toUpperCase());
        model.addAttribute("lname", lastName.toUpperCase());
        model.addAttribute("nationalId", nationalId);
        model.addAttribute("dob", dob);
        model.addAttribute("mobileNumber", mobileNumber);
        model.addAttribute("address", address);
        model.addAttribute("suburb", suburb);
        model.addAttribute("town", town);

        model.addAttribute(COMMISSION_RATE, new BigDecimal(params.get(COMMISSION_RATE)));
        model.addAttribute(ADMI_FEE_RATE, new BigDecimal(params.get(ADMI_FEE_RATE)));
        model.addAttribute(MONTHLY_INTEREST_RATE, new BigDecimal(params.get(MONTHLY_INTEREST_RATE)));
        model.addAttribute(MINIMUM_LOAN_TENOR, new BigDecimal(params.get(MINIMUM_LOAN_TENOR)));
        model.addAttribute(MAXIMUM_LOAN_TENOR, new BigDecimal(params.get(MAXIMUM_LOAN_TENOR)));
        model.addAttribute(MINIMUM_LOAN_AMOUNT, new BigDecimal(params.get(MINIMUM_LOAN_AMOUNT)));
        model.addAttribute(MAXIMUM_LOAN_AMOUNT, new BigDecimal(params.get(MAXIMUM_LOAN_AMOUNT)));
        model.addAttribute(DEFAULT_LOAN_AMOUNT, new BigDecimal(params.get(DEFAULT_LOAN_AMOUNT)));
        model.addAttribute(DEFAULT_LOAN_TENOR, new BigDecimal(params.get(DEFAULT_LOAN_TENOR)));
        model.addAttribute(PAYLOAD, payload);

        return "apply";
    }

    @PostMapping(value = "/apply", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String apply(@ModelAttribute LoanRequest loanRequest, Model model, HttpSession session) {

        loanRequest.setMobileNumber(String.valueOf(session.getAttribute("mobileNumber")));
        loanRequest.setFname(String.valueOf(session.getAttribute("fname")));
        loanRequest.setLname(String.valueOf(session.getAttribute("lname")));
        loanRequest.setNationalId(String.valueOf(session.getAttribute("nationalId")));

        final Address address = new Address();
        address.setCity(String.valueOf(session.getAttribute("town")));
        address.setStreet(String.valueOf(session.getAttribute("address")));
        address.setSuburb(String.valueOf(session.getAttribute("suburb")));
        address.setCountry("Zimbabwe");
        loanRequest.setAddress(address);

        LocalDate dateOfBirth = LocalDate.parse(String.valueOf(session.getAttribute("dob")), formatter);

        loanRequest.setMerchant(Merchant.DEFAULT_MERCHANT_CODE);
        loanRequest.setDateOfBirth(dateOfBirth);

        final EmploymentDetail employmentDetail = new EmploymentDetail();
        employmentDetail.setEmployerName("GOVERNMENT");
        employmentDetail.setGrossSalary(loanRequest.getGrossSalary());
        employmentDetail.setNetSalary(loanRequest.getNetSalary());
        loanRequest.setEmploymentDetail(employmentDetail);

        final NextOfKin nextOfKin = new NextOfKin();
        nextOfKin.setFirstName(capitalise(loanRequest.getNextOfKinName()));
        nextOfKin.setRelationship(loanRequest.getNextOfKinRelationShip());
        nextOfKin.setMobileNumber(loanRequest.getNextOfKinPhone());
        nextOfKin.setLastName("");
        nextOfKin.setNationalId(capitalise(loanRequest.getNextOfKinIdNumber()));

        Address nextOfKinAddress = new Address();
        nextOfKinAddress.setStreet(loanRequest.getNextOfKinAddress());
        nextOfKin.setAddress(nextOfKinAddress);
        loanRequest.setNextOfKin(nextOfKin);

        loanRequest.setChannelId(channelId);

        final LoanResponse loanResponse = loanService.requestLoan(loanRequest);

        model.addAttribute("internalReference", loanResponse.getInternalReference());
        model.addAttribute("payload", session.getAttribute(PAYLOAD));

        if (loanResponse.getLoanApprovalStatus() == LoanApprovalStatus.REJECTED) {
            return "fail";
        } else {
            // If the loan was approved, fetch the loan details to display
            try {
                Optional<Loan> approvedLoan = loanService.findByReference(loanResponse.getInternalReference());
                if (approvedLoan.isPresent()) {
                    addLoanDetailsToModel(model, approvedLoan.get());
                }
            } catch (Exception e) {
                log.error("Error fetching approved loan details", e);
            }
            return "success";
        }
    }

    /**
     * Helper method to add all loan details to the model
     */
    private void addLoanDetailsToModel(Model model, Loan loan) {
        model.addAttribute("loan", loan);
        // Add individual fields for easier access in the view
        model.addAttribute("loanReference", loan.getReference());
        model.addAttribute("loanApprovalStatus", loan.getLoanApprovalStatus());
        model.addAttribute("disbursementStatus", loan.getDisbursementStatus());
        model.addAttribute("disbursedAmount", loan.getDisbursedAmount());
        model.addAttribute("tenor", loan.getTenor());

        // Add formatted date strings to avoid Thymeleaf #dates formatting issues
        if (loan.getRepaymentStartDate() != null) {
            model.addAttribute("formattedStartDate",
                    loan.getRepaymentStartDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy")));
        }

        if (loan.getRepaymentEndDate() != null) {
            model.addAttribute("formattedEndDate",
                    loan.getRepaymentEndDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy")));
        }

        if (loan.getDateDisbursed() != null) {
            model.addAttribute("formattedDisbursementDate",
                    loan.getDateDisbursed().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy")));
        }

        // Add additional payment info
        if (loan.getMonthlyInstallment() != null) {
            model.addAttribute("monthlyPayment", loan.getMonthlyInstallment());
        }

        if (loan.getInterestRate() != null) {
            model.addAttribute("interestRate", loan.getInterestRate());
        }

        if (loan.getAgentCommission() != null) {
            model.addAttribute("adminFee", loan.getAgentCommission());
        }
    }

    public String capitalise(String text) {
        if (StringUtils.hasText(text)) {
            return text.toUpperCase();
        }
        return "";
    }

    @GetMapping("/loanDetails")
    public String loanDetails(@RequestParam(value = "reference", required = false) String reference, Model model) {
        if (reference != null && !reference.isEmpty()) {
            Optional<Loan> loan = loanService.findByReference(reference);
            if (loan.isPresent()) {
                addLoanDetailsToModel(model, loan.get());
            } else {
                model.addAttribute("errorMessage", "Loan not found");
            }
        }
        return "loan-details";
    }
}