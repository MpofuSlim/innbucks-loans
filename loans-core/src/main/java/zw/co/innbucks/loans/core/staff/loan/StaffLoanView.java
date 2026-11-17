package zw.co.innbucks.loans.core.staff.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
import zw.co.innbucks.loans.core.MsisdnUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A borrower's loan as the SuperApp shows it (FR-SGL-030): what was borrowed, what is still owed and when it is
 * collected, and the InnBucks wallet it is paid into, {@code payoutAccount}, shown only by its last four digits
 * ({@code ****3123}). {@code statusMessage} says where it stands in words the app shows as they are.
 */
public record StaffLoanView(String reference, StaffLoanStatus status, String statusMessage, BigDecimal amount,
                            String currency, BigDecimal totalRepayable, BigDecimal outstandingBalance,
                            LocalDate repaymentDate, LocalDateTime acceptedAt,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String payoutAccount) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    /**
     * Where {@code loan} stands on {@code today}, in words the app shows as they are: being paid into the borrower's
     * wallet however its payout stands (only a person ends one that is not paid, and a cancelled loan says so), then
     * paid, and when it is collected.
     */
    static String message(StaffLoan loan, LocalDate today) {
        String paidInto = "your " + StaffLoanPolicy.PayoutTarget.LABEL
                + (loan.getPayoutAccount() == null ? "" : " ending "
                + MsisdnUtils.lastFourDigits(loan.getPayoutAccount()));
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

    /** {@code ****3123}: how the borrower is shown the wallet the loan is paid into; null when it has none. */
    static String maskedPayoutAccount(StaffLoan loan) {
        return loan.getPayoutAccount() == null ? null : MsisdnUtils.mask(loan.getPayoutAccount());
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
