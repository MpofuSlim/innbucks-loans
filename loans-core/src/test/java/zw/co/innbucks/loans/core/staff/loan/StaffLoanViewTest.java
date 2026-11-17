package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the SuperApp tells a borrower about a loan (FR-SGL-030): the InnBucks wallet it is being paid, or was paid,
 * into, then when it is collected, or that it is overdue. Chipo Banda's SGL-2026-000144, USD 150.00 due on 20 November
 * 2026, paid into her wallet ending 3123.
 */
class StaffLoanViewTest {

    private static final LocalDate BEFORE_DUE = LocalDate.of(2026, 11, 9);
    private static final LocalDate DUE = LocalDate.of(2026, 11, 20);

    private static StaffLoan wallet(StaffLoanStatus status, StaffLoanPayoutStatus payout) {
        return StaffLoan.builder().reference("SGL-2026-000144").status(status).currency("USD")
                .amount(new BigDecimal("150.00")).totalRepayable(new BigDecimal("150.00")).dueDate(DUE)
                .payoutAccount("263772123123").payoutStatus(payout).build();
    }

    @ParameterizedTest
    @EnumSource(value = StaffLoanPayoutStatus.class, names = {"READY", "SENDING", "BOOKED", "IN_DOUBT", "REFUSED"})
    @DisplayName("a loan awaiting payout is being paid into the borrower's wallet, however its payout stands")
    void awaiting(StaffLoanPayoutStatus payout) {
        assertThat(StaffLoanView.message(wallet(StaffLoanStatus.AWAITING_DISBURSEMENT, payout), BEFORE_DUE))
                .isEqualTo("Your loan is approved. USD 150.00 is being paid into your InnBucks wallet ending 3123.");
    }

    @Test
    @DisplayName("a loan paid out says where it went, then when it is collected, or that it is overdue")
    void paid() {
        StaffLoan paid = wallet(StaffLoanStatus.DISBURSED, StaffLoanPayoutStatus.PAID);

        assertThat(StaffLoanView.message(paid, BEFORE_DUE)).isEqualTo("USD 150.00 was paid into your InnBucks"
                + " wallet ending 3123. USD 150.00 will be collected from your salary on 20 Nov 2026.");
        assertThat(StaffLoanView.message(paid, DUE.plusDays(1))).isEqualTo("USD 150.00 was paid into your"
                + " InnBucks wallet ending 3123. USD 150.00 was due from your salary on 20 Nov 2026 and is still"
                + " owed. Please contact the Credit department.");
    }

    @Test
    @DisplayName("a loan cancelled, repaid or written off keeps the words every loan has")
    void closed() {
        for (StaffLoanPayoutStatus ended : new StaffLoanPayoutStatus[]{StaffLoanPayoutStatus.WITHDRAWN,
                StaffLoanPayoutStatus.FAILED}) {
            assertThat(StaffLoanView.message(wallet(StaffLoanStatus.CANCELLED, ended), BEFORE_DUE))
                    .isEqualTo("This loan was cancelled before it was paid out. Nothing is owed.");
        }
        assertThat(StaffLoanView.message(wallet(StaffLoanStatus.REPAID, StaffLoanPayoutStatus.PAID), DUE))
                .isEqualTo("Repaid in full. Thank you.");
        assertThat(StaffLoanView.message(wallet(StaffLoanStatus.WRITTEN_OFF, StaffLoanPayoutStatus.PAID),
                DUE.plusDays(40))).isEqualTo("Please contact the Credit department about this loan.");
    }

    @Test
    @DisplayName("the borrower sees the wallet by its last four digits only")
    void walletMasked() {
        assertThat(StaffLoanView.maskedPayoutAccount(wallet(StaffLoanStatus.AWAITING_DISBURSEMENT,
                StaffLoanPayoutStatus.READY))).isEqualTo("****3123");
    }

    @Test
    @DisplayName("a loan accepted before vouchers were withdrawn has no wallet: its words never name one")
    void acceptedBeforeVouchersWereWithdrawn() {
        StaffLoan legacy = StaffLoan.builder().reference("SGL-2026-000143").status(StaffLoanStatus.CANCELLED)
                .currency("USD").amount(new BigDecimal("300.00")).totalRepayable(new BigDecimal("300.00"))
                .dueDate(DUE).build();

        assertThat(StaffLoanView.message(legacy, BEFORE_DUE))
                .isEqualTo("This loan was cancelled before it was paid out. Nothing is owed.");
        assertThat(StaffLoanView.maskedPayoutAccount(legacy)).isNull();
        assertThat(legacy.hasPayout()).isFalse();
    }
}
