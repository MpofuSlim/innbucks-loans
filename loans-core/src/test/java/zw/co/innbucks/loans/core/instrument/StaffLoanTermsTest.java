package zw.co.innbucks.loans.core.instrument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Staff Grocery Loan agreement's {@code {{payoutAccount}}}, and how the journey tells a cash agreement from a
 * voucher one: by the terms its wording names. Chipo Banda borrows USD 150.00, accepted on 1 October 2026.
 */
class StaffLoanTermsTest {

    private static StaffLoanTerms.Signing signing(String merchantName, int validityDays, String unredeemed,
                                                  String payoutAccount) {
        return new StaffLoanTerms.Signing("Chipo Banda", "E1012", "632223334C55", "263773456789", "Treasury",
                new BigDecimal("150.00"), "USD", BigDecimal.ZERO, new BigDecimal("150.00"),
                LocalDate.of(2026, 11, 20), merchantName, validityDays, unredeemed, payoutAccount,
                LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("{{payoutAccount}} names where a cash loan is paid, by its last four digits; blank for a voucher loan")
    void payoutAccount() {
        String body = "I, {{borrowerName}}, borrow {{currency}} {{amount}}, paid into my {{payoutAccount}}.";

        assertThat(StaffLoanTerms.render(body, signing(null, 0, null, "InnBucks wallet ****6789")))
                .isEqualTo("I, Chipo Banda, borrow USD 150.00, paid into my InnBucks wallet ****6789.");
        assertThat(StaffLoanTerms.render(body, signing("GetMore Groceries", 30, "You still repay.", null)))
                .isEqualTo("I, Chipo Banda, borrow USD 150.00, paid into my .");
        assertThat(StaffLoanTerms.placeholders()).containsKey("payoutAccount");
        assertThat(StaffLoanTerms.placeholders().get("payoutAccount")).contains("****3123")
                .doesNotContainPattern("\\d{5,}");
        assertThat(StaffLoanTerms.unknownPlaceholders(body)).as("it may be published").isEmpty();
    }

    @Test
    @DisplayName("the terms a wording names, in order, each once; a name that is not a term is not one")
    void placeholdersIn() {
        assertThat(StaffLoanTerms.placeholdersIn("{{amount}} into {{ payoutAccount }} on {{repaymentDate}};"
                + " {{amount}} again, and {{notATerm}}."))
                .containsExactly("amount", "payoutAccount", "repaymentDate");
        assertThat(StaffLoanTerms.placeholdersIn("No terms at all.")).isEmpty();
        assertThat(StaffLoanTerms.placeholdersIn(null)).isEmpty();
    }

    @Test
    @DisplayName("what only a cash agreement names, and what only a voucher one does, are all terms, and never both")
    void cashAndVoucherTerms() {
        assertThat(StaffLoanTerms.placeholders()).containsKeys(StaffLoanTerms.CASH_ONLY.toArray(String[]::new))
                .containsKeys(StaffLoanTerms.VOUCHER_ONLY.toArray(String[]::new));
        assertThat(StaffLoanTerms.CASH_ONLY).containsExactly("payoutAccount")
                .doesNotContainAnyElementsOf(StaffLoanTerms.VOUCHER_ONLY);
        assertThat(StaffLoanTerms.VOUCHER_ONLY).containsExactlyInAnyOrder("merchantName", "voucherValidityDays",
                "unredeemedVoucherTerms");
    }
}
