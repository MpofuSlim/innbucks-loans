package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * 2026, voucher 7 at GetMore Groceries; and SGL-2026-000144, USD 150.00 paid in cash into her own account.
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
        assertThat(StaffLoanView.maskedPayoutAccount(paidOut())).as("a voucher loan has no account").isNull();
    }

    // --- Paid in cash: SGL-2026-000144, USD 150.00 into Chipo's wallet, no merchant read, no voucher ---

    private static StaffLoan cash(StaffLoanStatus status, StaffLoanPayoutDestination destination, String account,
                                  StaffLoanPayoutStatus payout) {
        return StaffLoan.builder().reference("SGL-2026-000144").status(status).currency("USD")
                .amount(new BigDecimal("150.00")).totalRepayable(new BigDecimal("150.00")).dueDate(DUE)
                .unredeemedVoucherTreatment(UnredeemedVoucherTreatment.DEBT_STANDS)
                .payoutMethod(StaffLoanPayoutMethod.CASH).payoutDestination(destination).payoutAccount(account)
                .payoutStatus(payout).build();
    }

    private static StaffLoan wallet(StaffLoanStatus status, StaffLoanPayoutStatus payout) {
        return cash(status, StaffLoanPayoutDestination.VEENGU_WALLET, "263772123123", payout);
    }

    @ParameterizedTest
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"READY", "SENDING", "BOOKED", "IN_DOUBT", "REFUSED"})
    @DisplayName("a cash loan awaiting payout is being paid into the borrower's own account, however its payout stands")
    void cashAwaiting(StaffLoanPayoutStatus payout) {
        assertThat(StaffLoanView.message(wallet(StaffLoanStatus.AWAITING_DISBURSEMENT, payout), null, BEFORE_DUE))
                .isEqualTo("Your loan is approved. USD 150.00 is being paid into your InnBucks wallet ending 3123.");
    }

    @Test
    @DisplayName("a cash loan paid out says where it went, then when it is collected, or that it is overdue")
    void cashPaid() {
        StaffLoan paid = wallet(StaffLoanStatus.DISBURSED, StaffLoanPayoutStatus.PAID);

        assertThat(StaffLoanView.message(paid, null, BEFORE_DUE)).isEqualTo("USD 150.00 was paid into your InnBucks"
                + " wallet ending 3123. USD 150.00 will be collected from your salary on 20 Nov 2026.");
        assertThat(StaffLoanView.message(paid, null, DUE.plusDays(1))).isEqualTo("USD 150.00 was paid into your"
                + " InnBucks wallet ending 3123. USD 150.00 was due from your salary on 20 Nov 2026 and is still"
                + " owed. Please contact the Credit department.");
        assertThat(StaffLoanView.message(cash(StaffLoanStatus.DISBURSED, StaffLoanPayoutDestination.BR_ACCOUNT,
                "1234567890", StaffLoanPayoutStatus.PAID), null, BEFORE_DUE))
                .startsWith("USD 150.00 was paid into your InnBucks account ending 7890. ");
    }

    @Test
    @DisplayName("a cash loan cancelled, repaid or written off keeps the words every loan has")
    void cashClosed() {
        for (StaffLoanPayoutStatus ended : new StaffLoanPayoutStatus[]{StaffLoanPayoutStatus.WITHDRAWN,
                StaffLoanPayoutStatus.FAILED}) {
            assertThat(StaffLoanView.message(wallet(StaffLoanStatus.CANCELLED, ended), null, BEFORE_DUE))
                    .isEqualTo("This loan was cancelled before it was paid out. Nothing is owed.");
        }
        assertThat(StaffLoanView.message(wallet(StaffLoanStatus.REPAID, StaffLoanPayoutStatus.PAID), null, DUE))
                .isEqualTo("Repaid in full. Thank you.");
        assertThat(StaffLoanView.message(wallet(StaffLoanStatus.WRITTEN_OFF, StaffLoanPayoutStatus.PAID), null,
                DUE.plusDays(40))).isEqualTo("Please contact the Credit department about this loan.");
    }

    @Test
    @DisplayName("the borrower sees the account a cash loan is paid into by its last four digits only")
    void cashAccountMasked() {
        assertThat(StaffLoanView.maskedPayoutAccount(wallet(StaffLoanStatus.AWAITING_DISBURSEMENT,
                StaffLoanPayoutStatus.READY))).isEqualTo("****3123");
        assertThat(StaffLoanView.maskedPayoutAccount(cash(StaffLoanStatus.DISBURSED,
                StaffLoanPayoutDestination.BR_ACCOUNT, "1234567890", StaffLoanPayoutStatus.PAID)))
                .isEqualTo("****7890");
    }
}
