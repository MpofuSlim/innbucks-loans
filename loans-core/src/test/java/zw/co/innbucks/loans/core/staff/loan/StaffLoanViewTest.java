package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.loan.DisbursementType;
import zw.co.innbucks.loans.core.merchant.Merchant;
import zw.co.innbucks.loans.core.voucher.BorrowerVoucherResponse;
import zw.co.innbucks.loans.core.voucher.VoucherCodeSource;
import zw.co.innbucks.loans.core.voucher.VoucherStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the SuperApp tells a borrower about a loan (FR-SGL-030): once it is paid out, the words follow its voucher,
 * and then when it is collected, or that it is overdue; while it waits for its GetMore voucher, that it is coming.
 * Chipo Banda's SGL-2026-000143, USD 300.00 due on 20 November 2026, voucher 7 at GetMore Groceries.
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
                LocalDateTime.of(2026, 11, 5, 21, 59, 59), "**** **** **** 8406", null, null, VoucherCodeSource.LOANS,
                true, new BigDecimal("300.00").subtract(new BigDecimal(balance)), null, null);
    }

    @Test
    @DisplayName("a voucher that can be spent says so, with what is left once some is spent")
    void openVoucher() {
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.ISSUED, "300.00"), null, BEFORE_DUE))
                .isEqualTo("Your voucher is ready to spend at GetMore Groceries.");
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.PARTIALLY_REDEEMED, "120.00"), null,
                BEFORE_DUE))
                .isEqualTo("You have USD 120.00 left to spend at GetMore Groceries.");
    }

    @Test
    @DisplayName("a spent voucher is no longer offered for spending: the borrower is told when the loan is collected")
    void spentVoucher() {
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.REDEEMED, "0.00"), null, BEFORE_DUE))
                .isEqualTo("You have spent your voucher. USD 300.00 will be collected from your salary on"
                        + " 20 Nov 2026.");
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.REDEEMED, "0.00"), null, DUE))
                .as("on the day itself it is still to be collected")
                .endsWith("will be collected from your salary on 20 Nov 2026.");
    }

    @Test
    @DisplayName("an expired voucher says what went unspent, and what is repaid under the loan's own terms")
    void expiredVoucher() {
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.EXPIRED, "120.00"), null, BEFORE_DUE))
                .isEqualTo("Your voucher expired with USD 120.00 unspent, which can no longer be used. You still"
                        + " repay the full amount. USD 300.00 will be collected from your salary on 20 Nov 2026.");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.DISBURSED,
                        UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT), voucher(VoucherStatus.EXPIRED, "120.00"),
                null, BEFORE_DUE))
                .isEqualTo("Your voucher expired with USD 120.00 unspent, which can no longer be used. You repay"
                        + " only what you spent. It will be collected from your salary on 20 Nov 2026.");
    }

    @Test
    @DisplayName("past its due date a paid-out loan still unpaid is overdue, not 'will be collected'")
    void overdue() {
        LocalDate after = DUE.plusDays(1);
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.REDEEMED, "0.00"), null, after))
                .isEqualTo("You have spent your voucher. USD 300.00 was due from your salary on 20 Nov 2026 and is"
                        + " still owed. Please contact the Credit department.");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.DISBURSED,
                        UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT), voucher(VoucherStatus.EXPIRED, "120.00"),
                null, after))
                .endsWith("You repay only what you spent. It was due from your salary on 20 Nov 2026 and is still"
                        + " owed. Please contact the Credit department.");
    }

    @Test
    @DisplayName("a cancelled voucher, or none yet, is said plainly")
    void cancelledOrNoVoucher() {
        assertThat(StaffLoanView.message(paidOut(), voucher(VoucherStatus.CANCELLED, "300.00"), null, BEFORE_DUE))
                .isEqualTo("Your voucher was cancelled. Please contact the Credit department about this loan.");
        assertThat(StaffLoanView.message(paidOut(), null, null, BEFORE_DUE))
                .isEqualTo("Your loan has been paid out to GetMore Groceries. Your voucher will be sent to you.");
    }

    @Test
    @DisplayName("the other statuses keep their words, whatever the voucher")
    void otherStatuses() {
        BorrowerVoucherResponse expired = voucher(VoucherStatus.EXPIRED, "120.00");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.AWAITING_DISBURSEMENT,
                UnredeemedVoucherTreatment.DEBT_STANDS), null, null, BEFORE_DUE))
                .isEqualTo("Your loan is approved. Your voucher will be sent to you once the loan is paid out to"
                        + " GetMore Groceries.");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.REPAID, UnredeemedVoucherTreatment.DEBT_STANDS),
                expired, null, DUE.plusDays(1))).isEqualTo("Repaid in full. Thank you.");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.CANCELLED, UnredeemedVoucherTreatment.DEBT_STANDS),
                null, null, BEFORE_DUE)).isEqualTo("This loan was cancelled before it was paid out. Nothing is owed.");
        assertThat(StaffLoanView.message(loan(StaffLoanStatus.WRITTEN_OFF, UnredeemedVoucherTreatment.DEBT_STANDS),
                expired, null, DUE.plusDays(40))).isEqualTo("Please contact the Credit department about this loan.");
    }

    @Test
    @DisplayName("waiting for its GetMore voucher: coming shortly, or delayed and Credit knows; without one, as before")
    void awaitingAGetMoreVoucher() {
        StaffLoan awaiting = loan(StaffLoanStatus.AWAITING_DISBURSEMENT, UnredeemedVoucherTreatment.DEBT_STANDS);

        assertThat(StaffLoanView.message(awaiting, null, StaffLoanView.VoucherIssuance.PENDING, BEFORE_DUE))
                .isEqualTo("Your loan is approved. Your voucher is being prepared and will appear here shortly.");
        assertThat(StaffLoanView.message(awaiting, null, StaffLoanView.VoucherIssuance.DELAYED, BEFORE_DUE))
                .isEqualTo("Your loan is approved. There is a delay preparing your voucher; the Credit department has"
                        + " been told.");
        assertThat(StaffLoanView.message(awaiting, null, null, BEFORE_DUE)).as("GetMore OFF: unchanged")
                .isEqualTo("Your loan is approved. Your voucher will be sent to you once the loan is paid out to"
                        + " GetMore Groceries.");
    }

    @Test
    @DisplayName("each issuance state as the borrower is told it: being made is PENDING, parked is DELAYED")
    void issuanceAsTheBorrowerSeesIt() {
        StaffLoanVoucherIssuance issuance = StaffLoanVoucherIssuance.builder()
                .status(StaffLoanVoucherIssuanceStatus.PENDING).build();
        assertThat(StaffLoanView.VoucherIssuance.of(issuance)).isEqualTo(StaffLoanView.VoucherIssuance.PENDING);
        issuance.claim(LocalDateTime.of(2026, 10, 14, 8, 20, 5));
        assertThat(StaffLoanView.VoucherIssuance.of(issuance)).isEqualTo(StaffLoanView.VoucherIssuance.PENDING);
        issuance.park("UNAVAILABLE_AUTH", null, LocalDateTime.of(2026, 10, 14, 8, 20, 6));
        assertThat(StaffLoanView.VoucherIssuance.of(issuance)).isEqualTo(StaffLoanView.VoucherIssuance.DELAYED);
        issuance.cancelling(LocalDateTime.of(2026, 10, 14, 8, 20, 7));
        assertThat(StaffLoanView.VoucherIssuance.of(issuance)).isNull();
        assertThat(StaffLoanView.VoucherIssuance.of(null)).isNull();
    }

    @Test
    @DisplayName("cancelled after its GetMore voucher was issued: the voucher is withdrawn and nothing is owed")
    void cancelledWithAWithdrawnVoucher() {
        StaffLoan cancelled = StaffLoan.builder().reference("SGL-2026-000164").status(StaffLoanStatus.CANCELLED)
                .merchant(loan(StaffLoanStatus.CANCELLED, UnredeemedVoucherTreatment.DEBT_STANDS).getMerchant())
                .disbursedAt(LocalDateTime.of(2026, 10, 14, 8, 20, 7)).disbursementReference("GETMORE-SIM-1207")
                .build();

        assertThat(StaffLoanView.message(cancelled, null, null, BEFORE_DUE))
                .isEqualTo("This loan was cancelled and its voucher withdrawn. Nothing is owed.");
    }

    @Test
    @DisplayName("a voucher spent in one purchase (GetMore LIVE, single use) says so while it is unspent")
    void singleUseVoucher() {
        BorrowerVoucherResponse singleUse = new BorrowerVoucherResponse(VoucherStatus.ISSUED, new BigDecimal("150.00"),
                new BigDecimal("150.00"), "USD", LocalDateTime.of(2026, 11, 13, 21, 59, 59), "****8B1C", null, null,
                VoucherCodeSource.GETMORE, false, new BigDecimal("0.00"), null, LocalDateTime.of(2026, 10, 14, 8, 20,
                7));

        assertThat(StaffLoanView.message(paidOut(), singleUse, null, BEFORE_DUE))
                .isEqualTo("Your voucher is ready to spend at GetMore Groceries. Spend it in one purchase.");
    }
}
