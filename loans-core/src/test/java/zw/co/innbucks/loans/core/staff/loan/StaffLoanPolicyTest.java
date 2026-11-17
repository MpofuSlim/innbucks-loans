package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import zw.co.innbucks.loans.core.instrument.StaffLoanTerms;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Staff Grocery Loan's terms from its settings: which amounts an offer can be drawn in (FR-SGL-012), the due date
 * (the 20th of the following month), and what is owed and disclosed (FR-SGL-026).
 */
class StaffLoanPolicyTest {

    private final StaffLoanProperties properties = new StaffLoanProperties();
    private final StaffLoanPolicy policy = new StaffLoanPolicy(properties);

    @ParameterizedTest
    @ValueSource(strings = {"300.00", "300", "20.00", "20", "25", "295.00", "150.00"})
    @DisplayName("the full offer, or from the minimum in steps of the increment")
    void allowedAmounts(String amount) {
        assertThat(policy.amountProblem(new BigDecimal("300.00"), new BigDecimal(amount))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "300.01, You can borrow up to USD 300.00. Choose an amount from USD 20.00 to USD 300.00 in steps of"
                    + " USD 5.00",
            "15.00, Choose an amount from USD 20.00 to USD 300.00 in steps of USD 5.00",
            "22.00, Choose an amount from USD 20.00 to USD 300.00 in steps of USD 5.00",
            "100.001, Choose an amount from USD 20.00 to USD 300.00 in steps of USD 5.00",
            "0, Choose an amount from USD 20.00 to USD 300.00 in steps of USD 5.00",
            "-10, Choose an amount from USD 20.00 to USD 300.00 in steps of USD 5.00"})
    @DisplayName("over the offer, under the minimum, between steps, or a part of a cent: refused, with what is allowed")
    void refusedAmounts(String amount, String problem) {
        assertThat(policy.amountProblem(new BigDecimal("300.00"), new BigDecimal(amount))).contains(problem);
    }

    @Test
    @DisplayName("an offer off the steps can still be taken in full; one under the minimum only in full")
    void oddOffers() {
        assertThat(policy.amountProblem(new BigDecimal("333.33"), new BigDecimal("333.33"))).isEmpty();
        assertThat(policy.amountProblem(new BigDecimal("333.33"), new BigDecimal("330.00"))).isEmpty();
        assertThat(policy.amountProblem(new BigDecimal("333.33"), new BigDecimal("333.30"))).isPresent();

        assertThat(policy.drawRules(new BigDecimal("18.00"))).isEqualTo(new DrawRules(new BigDecimal("18.00"),
                new BigDecimal("5.00"), new BigDecimal("18.00"), "USD"));
        assertThat(policy.amountProblem(new BigDecimal("18.00"), new BigDecimal("18.00"))).isEmpty();
        assertThat(policy.amountProblem(new BigDecimal("18.00"), new BigDecimal("15.00"))).isPresent();
    }

    @Test
    @DisplayName("the draw rules follow the settings")
    void configurable() {
        properties.setMinimumDraw(new BigDecimal("50.00"));
        properties.setDrawIncrement(new BigDecimal("25.00"));

        assertThat(policy.drawRules(new BigDecimal("300.00"))).isEqualTo(new DrawRules(new BigDecimal("50.00"),
                new BigDecimal("25.00"), new BigDecimal("300.00"), "USD"));
        assertThat(policy.amountProblem(new BigDecimal("300.00"), new BigDecimal("75.00"))).isEmpty();
        assertThat(policy.amountProblem(new BigDecimal("300.00"), new BigDecimal("60.00"))).isPresent();
        assertThat(properties.isMinimumDrawAStep()).isTrue();
        properties.setMinimumDraw(new BigDecimal("30.00"));
        assertThat(properties.isMinimumDrawAStep()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"2026-10-01, 2026-11-20", "2026-10-31, 2026-11-20", "2026-12-21, 2027-01-20",
            "2027-01-31, 2027-02-20", "2026-10-20, 2026-11-20"})
    @DisplayName("due on the 20th of the month after acceptance, whatever the day")
    void dueDate(LocalDate accepted, LocalDate due) {
        assertThat(policy.dueDate(accepted)).isEqualTo(due);
    }

    @Test
    @DisplayName("the repayment day is a setting")
    void repaymentDay() {
        properties.setRepaymentDay(19);

        assertThat(policy.dueDate(LocalDate.of(2026, 10, 1))).isEqualTo(LocalDate.of(2026, 11, 19));
    }

    private static StaffMember chipo(String msisdn) {
        return StaffMember.builder().id(2L).employeeNumber("E1012").fullName("Chipo Banda")
                .nationalId("632223334C55").msisdn(msisdn).department("Treasury").grade("C4")
                .walletAccountNumber("1234567890").employmentStatus(StaffEmploymentStatus.ACTIVE).build();
    }

    @Test
    @DisplayName("the agreement's terms: the register's record, the amount owed in full, no interest, the 20th, the"
            + " wallet by its last four digits")
    void signing() {
        StaffMember member = chipo("263773456789");
        StaffLoanPolicy.PayoutTarget target = policy.payoutTarget(member).orElseThrow();

        StaffLoanTerms.Signing terms = policy.signing(member, new BigDecimal("150"), LocalDate.of(2026, 10, 1),
                target);

        assertThat(terms).isEqualTo(new StaffLoanTerms.Signing("Chipo Banda", "E1012", "632223334C55",
                "263773456789", "Treasury", new BigDecimal("150.00"), "USD", BigDecimal.ZERO,
                new BigDecimal("150.00"), LocalDate.of(2026, 11, 20), "InnBucks wallet ****6789",
                LocalDate.of(2026, 10, 1)));
    }

    @Test
    @DisplayName("the agreement is filled from the terms; amounts with two decimals, a 0% rate as 0")
    void rendering() {
        StaffLoanTerms.Signing terms = new StaffLoanTerms.Signing("Chipo Banda", "E1012", "632223334C55",
                "263773456789", "Treasury", new BigDecimal("300.00"), "USD", BigDecimal.ZERO,
                new BigDecimal("300.00"), LocalDate.of(2026, 11, 20), "InnBucks wallet ****6789",
                LocalDate.of(2026, 10, 1));

        assertThat(StaffLoanTerms.render("I, {{ borrowerName }} ({{employeeNumber}}), borrow {{currency}} {{amount}}"
                + " at {{interestRate}}%, paid into my {{payoutAccount}}, and repay {{totalRepayable}} on"
                + " {{repaymentDate}}. Accepted {{acceptedDate}}.", terms))
                .isEqualTo("I, Chipo Banda (E1012), borrow USD 300.00 at 0%, paid into my InnBucks wallet ****6789,"
                        + " and repay 300.00 on 2026-11-20. Accepted 2026-10-01.");
        assertThat(StaffLoanTerms.placeholders()).containsKeys("borrowerName", "repaymentDate", "totalRepayable",
                "payoutAccount").doesNotContainKeys("tenor", "monthlyInstalment", "merchantName",
                "voucherValidityDays", "unredeemedVoucherTerms");
        assertThat(StaffLoanTerms.REQUIRED).containsExactly("payoutAccount");
    }

    @Test
    @DisplayName("a wording written for the withdrawn voucher names terms that no longer exist")
    void voucherTermsAreUnknown() {
        assertThat(StaffLoanTerms.unknownPlaceholders("Spend at {{merchantName}} within {{voucherValidityDays}}"
                + " days, paid into {{payoutAccount}}. {{unredeemedVoucherTerms}}"))
                .containsExactly("merchantName", "voucherValidityDays", "unredeemedVoucherTerms");
    }

    @Test
    @DisplayName("paid into the wallet: the registered phone, the one the borrower proves with their PIN")
    void walletTarget() {
        StaffLoanPolicy.PayoutTarget target = policy.payoutTarget(chipo("263773456789")).orElseThrow();

        assertThat(target.account()).isEqualTo("263773456789");
        assertThat(target.masked()).isEqualTo("****6789");
        assertThat(target.last4()).isEqualTo("6789");
        assertThat(target.named()).isEqualTo("InnBucks wallet ****6789");
        assertThat(target.toString()).doesNotContain("263773456789").contains("****6789");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0773456789", "+263773456789", "263243456789", "26377345678", "2637734567890",
            "1234567890", " "})
    @DisplayName("a phone the register holds in no wallet shape is no target: the loan cannot be paid")
    void noWalletNoTarget(String msisdn) {
        assertThat(policy.payoutTarget(chipo(msisdn))).isEmpty();
    }

    @Test
    @DisplayName("no phone, no member: no target")
    void nothingToPay() {
        assertThat(policy.payoutTarget(chipo(null))).isEmpty();
        assertThat(policy.payoutTarget(null)).isEmpty();
    }
}
