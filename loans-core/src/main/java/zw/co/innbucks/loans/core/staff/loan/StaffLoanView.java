package zw.co.innbucks.loans.core.staff.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.MsisdnUtils;
import zw.co.innbucks.loans.core.voucher.BorrowerVoucherResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A borrower's loan as the SuperApp shows it (FR-SGL-030): what was borrowed, what is still owed and when it is
 * collected, and the voucher once it is issued, with its code while it can be spent. {@code statusMessage} says where
 * it stands in words the app shows as they are.
 *
 * <p>A loan paid in CASH says so ({@code payoutMethod} CASH): it has no merchant and no voucher (both null), and is
 * paid into the borrower's own account, {@code payoutAccount}, shown only by its last four digits ({@code ****3123}).
 * A voucher loan carries neither field, so it reads exactly as it did before cash payouts existed.
 */
public record StaffLoanView(String reference, StaffLoanStatus status, String statusMessage, BigDecimal amount,
                            String currency, BigDecimal totalRepayable, BigDecimal outstandingBalance,
                            LocalDate repaymentDate, String merchantName, LocalDateTime acceptedAt,
                            BorrowerVoucherResponse voucher,
                            @JsonInclude(JsonInclude.Include.NON_NULL) StaffLoanPayoutMethod payoutMethod,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String payoutAccount) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    /**
     * Where {@code loan} stands on {@code today}, in words the app shows as they are. Once a voucher loan is paid out
     * the words follow its voucher: what is left to spend, that it is spent, or what became of what was not, and then
     * when the loan is collected, or that it is overdue. A cash loan says where the money is being paid, or was, and
     * then when it is collected.
     *
     * @param voucher the voucher it was paid as, or null when there is none yet (always, for a cash loan)
     */
    static String message(StaffLoan loan, BorrowerVoucherResponse voucher, LocalDate today) {
        if (loan.getPayoutMethod() == StaffLoanPayoutMethod.CASH) {
            return cashMessage(loan, today);
        }
        String merchantName = loan.getMerchant().getCompanyName();
        return switch (loan.getStatus()) {
            case AWAITING_DISBURSEMENT -> "Your loan is approved. Your voucher will be sent to you once the loan is"
                    + " paid out to " + merchantName + ".";
            case DISBURSED -> paidOut(loan, merchantName, voucher, today);
            case REPAID -> "Repaid in full. Thank you.";
            case CANCELLED -> "This loan was cancelled before it was paid out. Nothing is owed.";
            case WRITTEN_OFF -> "Please contact the Credit department about this loan.";
        };
    }

    private static String paidOut(StaffLoan loan, String merchantName, BorrowerVoucherResponse voucher,
                                  LocalDate today) {
        if (voucher == null) {
            return "Your loan has been paid out to " + merchantName + ". Your voucher will be sent to you.";
        }
        String full = money(loan.getCurrency(), loan.getTotalRepayable());
        return switch (voucher.status()) {
            case ISSUED -> "Your voucher is ready to spend at " + merchantName + ".";
            case PARTIALLY_REDEEMED -> "You have " + money(voucher.currency(), voucher.balance()) + " left to spend"
                    + " at " + merchantName + ".";
            case REDEEMED -> "You have spent your voucher. " + repayment(full, loan.getDueDate(), today);
            case EXPIRED -> "Your voucher expired with " + money(voucher.currency(), voucher.balance()) + " unspent,"
                    + " which can no longer be used. " + switch (loan.getUnredeemedVoucherTreatment()) {
                case DEBT_STANDS -> "You still repay the full amount. " + repayment(full, loan.getDueDate(), today);
                case REDUCED_TO_AMOUNT_SPENT -> "You repay only what you spent. "
                        + repayment("It", loan.getDueDate(), today);
            };
            case CANCELLED -> "Your voucher was cancelled. Please contact the Credit department about this loan.";
        };
    }

    /**
     * A cash loan: being paid into the borrower's account however its payout stands (only a person ends one that is
     * not paid, and a cancelled loan says so), then paid, and when it is collected.
     */
    private static String cashMessage(StaffLoan loan, LocalDate today) {
        String paidInto = "your " + (loan.getPayoutDestination() == null ? "account"
                : loan.getPayoutDestination().label()) + " ending "
                + MsisdnUtils.lastFourDigits(loan.getPayoutAccount());
        String borrowed = money(loan.getCurrency(), loan.getAmount());
        return switch (loan.getStatus()) {
            case AWAITING_DISBURSEMENT -> "Your loan is approved. " + borrowed + " is being paid into " + paidInto
                    + ".";
            case DISBURSED -> borrowed + " was paid into " + paidInto + ". "
                    + repayment(money(loan.getCurrency(), loan.getTotalRepayable()), loan.getDueDate(), today);
            case REPAID -> "Repaid in full. Thank you.";
            case CANCELLED -> "This loan was cancelled before it was paid out. Nothing is owed.";
            case WRITTEN_OFF -> "Please contact the Credit department about this loan.";
        };
    }

    /** {@code ****3123}: how the borrower is shown the account a cash loan is paid into; null for a voucher loan. */
    static String maskedPayoutAccount(StaffLoan loan) {
        return loan.getPayoutMethod() == StaffLoanPayoutMethod.CASH ? MsisdnUtils.mask(loan.getPayoutAccount()) : null;
    }

    /** When {@code what} is collected, or, past the due date with the loan still unpaid, that it is overdue. */
    private static String repayment(String what, LocalDate due, LocalDate today) {
        return today.isAfter(due)
                ? what + " was due from your salary on " + DAY.format(due) + " and is still owed. Please contact the"
                + " Credit department."
                : what + " will be collected from your salary on " + DAY.format(due) + ".";
    }

    private static String money(String currency, BigDecimal amount) {
        return currency + " " + amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
