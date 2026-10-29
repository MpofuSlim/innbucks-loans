package zw.co.innbucks.loans.core.disbursements;

import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.merchant.MerchantService;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A pre-approved loan to book with InnBucks that is not an SSB application: a Staff Grocery Loan paid in cash
 * ({@link InnbucksDisbursementService#bookPreApproved}). Only what the register holds is sent; every field left null is
 * left out of the request.
 *
 * @param participantReference our reference for it, which the bank's inquiry is keyed by
 * @param amount               in major units; sent in cents
 * @param loanType             the booking's {@code type}: PERSONAL or CONSUMER_FINANCE
 * @param msisdn               the InnBucks customer whose wallet the booked loan pays
 * @param settlementAccount    the account it pays instead, when it is paid into an account; null for a wallet
 */
public record PreApprovedLoanBooking(String participantReference, String firstName, String lastName,
                                     String idNumber, BigDecimal amount, String currency, String product,
                                     String loanType, int tenureInMonths, String msisdn, String settlementAccount,
                                     String employer, String employerNumber, LocalDate employmentStartDate) {

    /** Names no ID number, and the phone and account by their last four digits only. */
    @Override
    public String toString() {
        return "PreApprovedLoanBooking[participantReference=" + participantReference + ", amount=" + amount
                + ", currency=" + currency + ", product=" + product + ", loanType=" + loanType + ", tenureInMonths="
                + tenureInMonths + ", msisdn=" + MsisdnUtils.mask(msisdn) + ", settlementAccount="
                + (settlementAccount == null ? null : MerchantService.maskAccountNumber(settlementAccount)) + "]";
    }
}
