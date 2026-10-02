package zw.co.innbucks.loans.core.staff.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import zw.co.innbucks.loans.core.instrument.StaffLoanTerms;
import zw.co.innbucks.loans.core.staff.StaffEmploymentStatus;
import zw.co.innbucks.loans.core.staff.StaffMember;
import zw.co.innbucks.loans.core.voucher.VoucherProperties;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Staff Grocery Loan's terms from its settings: which amounts an offer can be drawn in (FR-SGL-012), the due date
 * (the 20th of the following month), and what is owed and disclosed (FR-SGL-026).
 */
class StaffLoanPolicyTest {

    private final StaffLoanProperties properties = new StaffLoanProperties();
    private final StaffLoanPolicy policy = new StaffLoanPolicy(properties, new VoucherProperties());

    @ParameterizedTest
    @ValueSource(strings = {"300.00", "300", "10.00", "10", "15", "295.00", "150.00"})
    @DisplayName("the full offer, or from the minimum in steps of the increment")
    void allowedAmounts(String amount) {
        assertThat(policy.amountProblem(new BigDecimal("300.00"), new BigDecimal(amount))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "300.01, You can borrow up to USD 300.00. Choose an amount from USD 10.00 to USD 300.00 in steps of"
                    + " USD 5.00",
            "5.00, Choose an amount from USD 10.00 to USD 300.00 in steps of USD 5.00",
            "12.00, Choose an amount from USD 10.00 to USD 300.00 in steps of USD 5.00",
            "100.001, Choose an amount from USD 10.00 to USD 300.00 in steps of USD 5.00",
            "0, Choose an amount from USD 10.00 to USD 300.00 in steps of USD 5.00",
            "-10, Choose an amount from USD 10.00 to USD 300.00 in steps of USD 5.00"})
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

        assertThat(policy.drawRules(new BigDecimal("8.00"))).isEqualTo(new DrawRules(new BigDecimal("8.00"),
                new BigDecimal("5.00"), new BigDecimal("8.00"), "USD"));
        assertThat(policy.amountProblem(new BigDecimal("8.00"), new BigDecimal("8.00"))).isEmpty();
        assertThat(policy.amountProblem(new BigDecimal("8.00"), new BigDecimal("5.00"))).isPresent();
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

    @Test
    @DisplayName("the agreement's terms: the register's record, the amount owed in full, no interest, the 20th, the"
            + " merchant given")
    void signing() {
        StaffMember chipo = StaffMember.builder().id(2L).employeeNumber("E1012").fullName("Chipo Banda")
                .nationalId("632223334C55").msisdn("263773456789").department("Treasury").grade("C4")
                .employmentStatus(StaffEmploymentStatus.ACTIVE).build();

        StaffLoanTerms.Signing terms = policy.signing(chipo, new BigDecimal("300"), LocalDate.of(2026, 10, 1),
                "GetMore Groceries");

        assertThat(terms).isEqualTo(new StaffLoanTerms.Signing("Chipo Banda", "E1012", "632223334C55",
                "263773456789", "Treasury", new BigDecimal("300.00"), "USD", BigDecimal.ZERO,
                new BigDecimal("300.00"), LocalDate.of(2026, 11, 20), "GetMore Groceries", 30,
                UnredeemedVoucherTreatment.DEBT_STANDS.terms(), LocalDate.of(2026, 10, 1)));
        properties.setUnredeemedVoucherTreatment(UnredeemedVoucherTreatment.REDUCED_TO_AMOUNT_SPENT);
        assertThat(policy.signing(chipo, new BigDecimal("300"), LocalDate.of(2026, 10, 1), "Pick n Pay"))
                .extracting(StaffLoanTerms.Signing::merchantName, StaffLoanTerms.Signing::unredeemedVoucherTerms)
                .containsExactly("Pick n Pay",
                        "If you do not spend the whole voucher before it expires, you repay only what you spent.");
    }

    @Test
    @DisplayName("the agreement is filled from the terms; amounts with two decimals, a 0% rate as 0")
    void rendering() {
        StaffLoanTerms.Signing terms = new StaffLoanTerms.Signing("Chipo Banda", "E1012", "632223334C55",
                "263773456789", "Treasury", new BigDecimal("300.00"), "USD", BigDecimal.ZERO,
                new BigDecimal("300.00"), LocalDate.of(2026, 11, 20), "GetMore Groceries", 30,
                UnredeemedVoucherTreatment.DEBT_STANDS.terms(), LocalDate.of(2026, 10, 1));

        assertThat(StaffLoanTerms.render("I, {{ borrowerName }} ({{employeeNumber}}), borrow {{currency}} {{amount}}"
                + " at {{interestRate}}% to spend at {{merchantName}} within {{voucherValidityDays}} days, and repay"
                + " {{totalRepayable}} on {{repaymentDate}}. {{unredeemedVoucherTerms}} Accepted {{acceptedDate}}.",
                terms)).isEqualTo("I, Chipo Banda (E1012), borrow USD 300.00 at 0% to spend at GetMore Groceries"
                + " within 30 days, and repay 300.00 on 2026-11-20. If you do not spend the whole voucher before it"
                + " expires, you still repay the full amount. Accepted 2026-10-01.");
        assertThat(StaffLoanTerms.placeholders()).containsKeys("borrowerName", "repaymentDate", "totalRepayable",
                "unredeemedVoucherTerms").doesNotContainKeys("tenor", "monthlyInstalment");
    }
}
