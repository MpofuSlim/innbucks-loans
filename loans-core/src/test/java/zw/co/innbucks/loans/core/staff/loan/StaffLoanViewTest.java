package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.voucher.BorrowerVoucherResponse;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the SuperApp tells a borrower about a loan (FR-SGL-030): once it is paid out, the words follow its voucher,
 * and then when it is collected, or that it is overdue. Chipo Banda's SGL-2026-000143, USD 300.00 due on 20 November
 * 2026, voucher 7 at GetMore Groceries.
 */
class StaffLoanViewTest {

    private static final LocalDate BEFORE_DUE = LocalDate.of(2026, 11, 9);
    private static final LocalDate DUE = LocalDate.of(2026, 11, 20);

    private static StaffLoan loan(StaffLoanStatus status, UnredeemedVoucherTreatment treatment) {
        Merchant getMore = Merchant.builder().merchantCode("getmore-groceries").companyName("GetMore Groceries")
                .disbursementType(DisbursementType.MERCHANT_MOBILE_WALLET).build();
        return StaffLoan.builder().reference("SGL-2026-000143").status(status).merchant(getMore).currency("USD")
                .amount(new BigDecimal("300.00")).totalRepayable(new BigDecimal("300.00")).dueDate(DUE)
                .unredeemedVoucherTreatment(treatment).build();
    }

    private static StaffLoan paidOut() {
        return loan(StaffLoanStatus.DISBURSED, UnredeemedVoucherTreatment.DEBT_STANDS);
    }

    private static BorrowerVoucherResponse voucher(VoucherStatus status, String balance) {
        return new BorrowerVoucherResponse(status, new BigDecimal("300.00"), new BigDecimal(balance), "USD",
                LocalDateTime.of(2026, 11, 5, 21, 59, 59), "**** **** **** 8406", null, null);
    }

    @Test
    @DisplayName("a voucher that can be spent says so, with what is left once some is spent")
    void openVoucher() {
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.ISSUED, "300.00"), BEFORE_DUE))
                .isEqualTo("Your voucher is ready to spend at GetMore Groceries.");
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.PARTIALLY_REDEEMED, "120.00"), BEFORE_DUE))
                .isEqualTo("You have USD 120.00 left to spend at GetMore Groceries.");
    }

    @Test
    @DisplayName("a spent voucher is no longer offered for spending: the borrower is told when the loan is collected")
    void spentVoucher() {
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.REDEEMED, "0.00"), BEFORE_DUE))
                .isEqualTo("You have spent your voucher. USD 300.00 will be collected from your salary on"
                        + " 20 Nov 2026.");
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.REDEEMED, "0.00"), DUE))
                .as("on the day itself it is still to be collected")
                .endsWith("will be collected from your salary on 20 Nov 2026.");
    }

    @Test
    @DisplayName("an expired voucher says what went unspent, and what is repaid under the loan's own terms")
    void expiredVoucher() {
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.EXPIRED, "120.00"), BEFORE_DUE))
                .isEqualTo("Your voucher expired with USD 120.00 unspent, which can no longer be used. You still"
                        + " repay the full amount. USD 300.00 will be collected from your salary on 20 Nov 2026.");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.DISBURSED,
                        UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT), voucher(VoucherStatus.EXPIRED, "120.00"),
                BEFORE_DUE))
                .isEqualTo("Your voucher expired with USD 120.00 unspent, which can no longer be used. You repay"
                        + " only what you spent. It will be collected from your salary on 20 Nov 2026.");
    }

    @Test
    @DisplayName("past its due date a paid-out loan still unpaid is overdue, not 'will be collected'")
    void overdue() {
        LocalDate after = DUE.plusDays(1);
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.REDEEMED, "0.00"), after))
                .isEqualTo("You have spent your voucher. USD 300.00 was due from your salary on 20 Nov 2026 and is"
                        + " still owed. Please contact the Credit department.");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.DISBURSED,
                        UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT), voucher(VoucherStatus.EXPIRED, "120.00"),
                after))
                .endsWith("You repay only what you spent. It was due from your salary on 20 Nov 2026 and is still"
                        + " owed. Please contact the Credit department.");
    }

    @Test
    @DisplayName("a cancelled voucher, or none yet, is said plainly")
    void cancelledOrNoVoucher() {
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.CANCELLED, "300.00"), BEFORE_DUE))
                .isEqualTo("Your voucher was cancelled. Please contact the Credit department about this loan.");
        assertThat(StaffLoanView.message(paidOut(), null, BEFORE_DUE))
                .isEqualTo("Your loan has been paid out to GetMore Groceries. Your voucher will be sent to you.");
    }

    @Test
    @DisplayName("the other statuses keep their words, whatever the voucher")
    void otherStatuses() {
        BorrowerVoucherResponse expired = voucher(VoucherStatus.EXPIRED, "120.00");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.AWAITING_DISBURSEMENT,
                UnredeemedVoucherTreatment.DEBT_STANDS), null, BEFORE_DUE))
                .isEqualTo("Your loan is approved. Your voucher will be sent to you once the loan is paid out to"
                        + " GetMore Groceries.");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.REPAID, UnredeemedVoucherTreatment.DEBT_STANDS),
                expired, DUE.plusDays(1))).isEqualTo("Repaid in full. Thank you.");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.CANCELLED, UnredeemedVoucherTreatment.DEBT_STANDS),
                null, BEFORE_DUE)).isEqualTo("This loan was cancelled before it was paid out. Nothing is owed.");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.WRITTEN_OFF, UnredeemedVoucherTreatment.DEBT_STANDS),
                expired, DUE.plusDays(40))).isEqualTo("Please contact the Credit department about this loan.");
    }
}
