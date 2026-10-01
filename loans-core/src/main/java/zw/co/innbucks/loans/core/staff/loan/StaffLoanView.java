package zw.co.innbucks.loans.core.staff.loan;

import zw.co.innbucks.loans.core.voucher.BorrowerVoucherResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A borrower's loan as the SuperApp shows it (FR-SGL-030): what was borrowed, what is still owed and when it is
 * collected, and the voucher once it is issued, with its code while it can be spent. {@code statusMessage} says where
 * it stands in words the app shows as they are.
 */
public record StaffLoanView(String reference, StaffLoanStatus status, String statusMessage, BigDecimal amount,
                            String currency, BigDecimal totalRepayable, BigDecimal outstandingBalance,
                            LocalDate repaymentDate, String merchantName, LocalDateTime acceptedAt,
                            BorrowerVoucherResponse voucher) {

    static String message(StaffLoanStatus status, String merchantName) {
        return switch (status) {
            case AWAITING_DISBURSEMENT -> "Your loan is approved. Your voucher will be sent to you once the loan is"
                    + " paid out to " + merchantName + ".";
            case DISBURSED -> "Your voucher is ready to spend at " + merchantName + ".";
            case REPAID -> "Repaid in full. Thank you.";
            case CANCELLED -> "This loan was cancelled before it was paid out. Nothing is owed.";
            case WRITTEN_OFF -> "Please contact the Credit department about this loan.";
        };
    }
}
