package zw.co.innbucks.loans.core.instrument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Staff Grocery Loan agreement's {@code {{payoutAccount}}}, the one term every agreement must name, and the terms
 * the withdrawn voucher's wording used, which no longer exist. Chipo Banda borrows USD 150.00, accepted on 1 October
 * 2026.
 */
class StaffLoanTermsTest {

    private static StaffLoanTerms.Signing signing(String payoutAccount) {
        return new StaffLoanTerms.Signing("Chipo Banda", "E1012", "632223334C55", "263773456789", "Treasury",
                new BigDecimal("150.00"), "USD", BigDecimal.ZERO, new BigDecimal("150.00"),
                LocalDate.of(2026, 11, 20), payoutAccount, LocalDate.of(2026, 10, 1));
    }

    @Test
    @DisplayName("{{payoutAccount}} names the wallet the loan is paid into, by its last four digits")
    void payoutAccount() {
        String body = "I, {{borrowerName}}, borrow {{currency}} {{amount}}, paid into my {{payoutAccount}}.";

        assertThat(StaffLoanTerms.render(body, signing("InnBucks wallet ****6789")))
                .isEqualTo("I, Chipo Banda, borrow USD 150.00, paid into my InnBucks wallet ****6789.");
        assertThat(StaffLoanTerms.placeholders()).containsKey("payoutAccount");
        assertThat(StaffLoanTerms.placeholders().get("payoutAccount")).contains("****3123")
                .doesNotContainPattern("\\d{5,}");
        assertThat(StaffLoanTerms.unknownPlaceholders(body)).as("it may be published").isEmpty();
        assertThat(StaffLoanTerms.REQUIRED).containsExactly("payoutAccount");
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
    @DisplayName("the withdrawn voucher's terms are terms no longer: a wording that names them is refused")
    void voucherTermsAreGone() {
        assertThat(StaffLoanTerms.placeholders()).doesNotContainKeys("merchantName", "voucherValidityDays",
                "unredeemedVoucherTerms");
        assertThat(StaffLoanTerms.unknownPlaceholders("Spend it at {{merchantName}} within {{voucherValidityDays}}"
                + " days, into {{payoutAccount}}. {{unredeemedVoucherTerms}}"))
                .containsExactly("merchantName", "voucherValidityDays", "unredeemedVoucherTerms");
    }
}
