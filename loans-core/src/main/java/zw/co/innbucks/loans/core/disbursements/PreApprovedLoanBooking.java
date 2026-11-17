package zw.co.innbucks.loans.core.disbursements;

import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.merchant.MerchantService;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A pre-approved loan to book with InnBucks that is not an SSB application: a Staff Grocery Loan paid in cash
 * ({@link InnbucksDisbursementService#bookPreApproved}). Every field left null is left out of the request.
 *
 * @param participantReference our reference for it, which the bank's inquiry is keyed by
 * @param amount               in major units; sent in dollars
 * @param loanType             the booking's {@code type}: PERSONAL or CONSUMER_FINANCE
 * @param msisdn               the InnBucks customer whose wallet the booked loan pays
 * @param settlementAccount    the account it pays, when one is named; null lets the bank find the wallet by msisdn
 * @param personal             the personal details the bank requires and the staff register does not hold; null
 *                             sends none of them
 * @param autoSettle           true asks the bank to pay the booked loan out itself
 */
public record PreApprovedLoanBooking(String participantReference, String firstName, String lastName,
                                     String idNumber, BigDecimal amount, String currency, String product,
                                     String loanType, int tenureInMonths, String msisdn, String settlementAccount,
                                     String employer, String employerNumber, LocalDate employmentStartDate,
                                     String businessLine, String loanPurpose, PersonalDetails personal,
                                     boolean autoSettle) {

    /**
     * What the bank requires about the borrower beyond the staff register.
     *
     * @param dateOfBirth  written as the bank writes dates, dd-MM-yyyy
     * @param grossSalary  in dollars
     */
    public record PersonalDetails(String address, String dateOfBirth, String maritalStatus, String placeOfBirth,
                                  BigDecimal grossSalary, String nextOfKinIdNumber, String nextOfKinFullName,
                                  String nextOfKinMsisdn, String nextOfKinAddress, String nextOfKinRelationship) {

        /** Names none of them: they are a person's details. */
        @Override
        public String toString() {
            return "PersonalDetails[...]";
        }
    }

    /** Names no ID number or personal details, and the phone and account by their last four digits only. */
    @Override
    public String toString() {
        return "PreApprovedLoanBooking[participantReference=" + participantReference + ", amount=" + amount
                + ", currency=" + currency + ", product=" + product + ", loanType=" + loanType + ", tenureInMonths="
                + tenureInMonths + ", msisdn=" + MsisdnUtils.mask(msisdn) + ", settlementAccount="
                + (settlementAccount == null ? null : MerchantService.maskAccountNumber(settlementAccount))
                + ", personal=" + (personal == null ? "none" : "given") + ", autoSettle=" + autoSettle + "]";
    }
}
