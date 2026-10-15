package zw.co.innbucks.loans.core.staff.loan;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param voucherIssuance while an accepted loan waits for its GetMore voucher: PENDING (being prepared) or DELAYED
 *                        (stopped for the Credit department); absent otherwise
 */
public record StaffLoanView(String reference, StaffLoanStatus status, String statusMessage, BigDecimal amount,
                            String currency, BigDecimal totalRepayable, BigDecimal outstandingBalance,
                            LocalDate repaymentDate, String merchantName, LocalDateTime acceptedAt,
                            BorrowerVoucherResponse voucher,
                            @JsonInclude(JsonInclude.Include.NON_NULL) VoucherIssuance voucherIssuance) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    /** Where an accepted loan's GetMore voucher stands, as the borrower is told it. */
    public enum VoucherIssuance {
        /** Being made at GetMore; it appears on the next read. */
        PENDING,
        /** Stopped for the Credit department, who have been told. */
        DELAYED;

        /** How the borrower is told {@code issuance} stands, or null when it is not waiting on their loan. */
        static VoucherIssuance of(StaffLoanVoucherIssuance issuance) {
            if (issuance == null) {
                return null;
            }
            return switch (issuance.getStatus()) {
                case PENDING, CALLING -> PENDING;
                case PARKED -> DELAYED;
                case ISSUED, CANCELLING, ABANDONED -> null;
            };
        }
    }

    /**
     * Where {@code loan} stands on {@code today}, in words the app shows as they are. Once it is paid out the words
     * follow its voucher: what is left to spend, that it is spent, or what became of what was not, and then when the
     * loan is collected, or that it is overdue. While it waits for its GetMore voucher, the words say it is coming, or
     * that it is delayed and Credit knows.
     *
     * @param voucher  the voucher it was paid as, or null when there is none yet
     * @param issuance how its GetMore voucher stands while it waits for it, or null
     */
    static String message(StaffLoan loan, BorrowerVoucherResponse voucher, VoucherIssuance issuance,
                          LocalDate today) {
        String merchantName = loan.getMerchant().getCompanyName();
        return switch (loan.getStatus()) {
            case AWAITING_DISBURSEMENT -> awaiting(merchantName, issuance);
            case DISBURSED -> paidOut(loan, merchantName, voucher, today);
            case REPAID -> "Repaid in full. Thank you.";
            case CANCELLED -> loan.getDisbursedAt() == null
                    ? "This loan was cancelled before it was paid out. Nothing is owed."
                    : "This loan was cancelled and its voucher withdrawn. Nothing is owed.";
            case WRITTEN_OFF -> "Please contact the Credit department about this loan.";
        };
    }

    private static String awaiting(String merchantName, VoucherIssuance issuance) {
        if (issuance == null) {
            return "Your loan is approved. Your voucher will be sent to you once the loan is paid out to "
                    + merchantName + ".";
        }
        return switch (issuance) {
            case PENDING -> "Your loan is approved. Your voucher is being prepared and will appear here shortly.";
            case DELAYED -> "Your loan is approved. There is a delay preparing your voucher; the Credit department"
                    + " has been told.";
        };
    }

    private static String paidOut(StaffLoan loan, String merchantName, BorrowerVoucherResponse voucher,
                                  LocalDate today) {
        if (voucher == null) {
            return "Your loan has been paid out to " + merchantName + ". Your voucher will be sent to you.";
        }
        String full = money(loan.getCurrency(), loan.getTotalRepayable());
        return switch (voucher.status()) {
            case ISSUED -> "Your voucher is ready to spend at " + merchantName + "."
                    + (voucher.partialSpending() ? "" : " Spend it in one purchase.");
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
