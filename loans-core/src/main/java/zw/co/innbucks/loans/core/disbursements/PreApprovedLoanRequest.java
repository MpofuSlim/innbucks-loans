package zw.co.innbucks.loans.core.disbursements;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.merchant.MerchantService;

/**
 * The body of a Staff Grocery Loan's {@code POST /bank/api/loan/apply/pre-approved}, in the order the bank's own
 * sample writes it. Separate from the SSB booking's {@link LoanAccountCreationRequest} on purpose: this one sends the
 * amount in dollars, as the bank confirmed for this call, and asks the bank to pay it out ({@code autoSettle}). A null
 * field is left out.
 *
 * @param amount      in dollars: 150 for USD 150.00, a fraction only when there is one (150.5)
 * @param grossSalary in dollars, like the amount
 * @param autoSettle  true: the bank pays the booked loan into the borrower's account itself
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"firstName", "lastName", "idNumber", "address", "dateOfBirth", "amount", "currency",
        "tenureInMonths", "repaymentFrequency", "product", "type", "maritalStatus", "msisdn", "participantReference",
        "employer", "nextOfKinIdNumber", "nextOfKinFullName", "nextOfKinMsisdn", "nextOfKinAddress", "employerNumber",
        "nextOfKinRelationship", "placeOfBirth", "employmentStartDate", "grossSalary", "businessLine", "loanPurpose",
        "settlementAccount", "autoSettle"})
public record PreApprovedLoanRequest(String firstName, String lastName, String idNumber, String address,
                                     String dateOfBirth, Number amount, String currency, Integer tenureInMonths,
                                     String repaymentFrequency, String product, String type, String maritalStatus,
                                     String msisdn, String participantReference, String employer,
                                     String nextOfKinIdNumber, String nextOfKinFullName, String nextOfKinMsisdn,
                                     String nextOfKinAddress, String employerNumber, String nextOfKinRelationship,
                                     String placeOfBirth, String employmentStartDate, Number grossSalary,
                                     String businessLine, String loanPurpose, String settlementAccount,
                                     Boolean autoSettle) {

    /** Names no ID number, address or next of kin, and the phone and account by their last four digits only. */
    @Override
    public String toString() {
        return "PreApprovedLoanRequest[participantReference=" + participantReference + ", amount=" + amount
                + ", currency=" + currency + ", product=" + product + ", type=" + type + ", tenureInMonths="
                + tenureInMonths + ", msisdn=" + MsisdnUtils.mask(msisdn) + ", settlementAccount="
                + (settlementAccount == null ? null : MerchantService.maskAccountNumber(settlementAccount))
                + ", autoSettle=" + autoSettle + "]";
    }
}
