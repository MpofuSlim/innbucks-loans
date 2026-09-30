package zw.co.innbucks.loans.core.instrument;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.innbucks.loans.core.loan.EmploymentDetail;
import zw.co.innbucks.loans.core.loan.Loan;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The placeholders instrument wording may use, and how a loan fills them (FR-SSB-013). */
class InstrumentTermsTest {

    private static final LocalDate SIGNED = LocalDate.of(2026, 9, 30);

    static Loan loan() {
        EmploymentDetail job = new EmploymentDetail();
        job.setEmployerName("Government of Zimbabwe");
        job.setMinistry("Ministry of Health and Child Care");
        job.setStation("Mpilo Central Hospital");
        job.setEmployeeNumber("7654321B");
        return Loan.builder()
                .firstName("Tatenda").lastName("Ncube").ecNumber("7654321B").nationalIdNumber("637654321B42")
                .mobileNumber("263772345678").walletNumber("263772345679")
                .employmentDetail(job)
                .principal(new BigDecimal("319.148936")).disbursedAmount(new BigDecimal("300"))
                .feeAmount(new BigDecimal("19.15")).feeRate(new BigDecimal("6.00"))
                .interestRate(new BigDecimal("7.0")).interestAmount(new BigDecimal("82.59"))
                .monthlyInstallment(new BigDecimal("66.96")).grossedMonthlyDeduction(new BigDecimal("69.03"))
                .tenor(6)
                .build();
    }

    @Test
    @DisplayName("every term is filled from the loan: amounts to two decimals, rates as written, the market day signed")
    void everyTermIsFilled() {
        String body = String.join("|", InstrumentTerms.placeholders().keySet().stream()
                .map(name -> "{{" + name + "}}").toList());

        String rendered = InstrumentTerms.render(body, loan(), SIGNED);

        assertThat(rendered.split("\\|", -1)).containsExactly(
                "Tatenda Ncube", "Tatenda", "Ncube", "7654321B", "637654321B42", "263772345678", "263772345679",
                "Government of Zimbabwe", "Ministry of Health and Child Care", "Mpilo Central Hospital", "7654321B",
                "300.00", "319.15", "19.15", "6", "7", "82.59", "6", "66.96", "69.03", "2026-09-30");
    }

    @Test
    @DisplayName("a term the loan does not carry is filled with nothing, never with 'null'")
    void absentTermsAreEmpty() {
        Loan bare = Loan.builder().firstName("Tatenda").tenor(6).build();

        String rendered = InstrumentTerms.render("[{{applicantName}}][{{ministry}}][{{amount}}][{{walletNumber}}]",
                bare, SIGNED);

        assertThat(rendered).isEqualTo("[Tatenda][][][]");
    }

    @Test
    @DisplayName("spaces inside the braces are allowed, and a value is inserted literally, never as a pattern")
    void valuesAreLiteral() {
        Loan loan = loan();
        loan.setFirstName("T$1 \\ Ncube");

        assertThat(InstrumentTerms.render("Dear {{ firstName }}, {{tenor}} months.", loan, SIGNED))
                .isEqualTo("Dear T$1 \\ Ncube, 6 months.");
    }

    @Test
    @DisplayName("names that are not terms are found, once each, in order; text in single braces is not a placeholder")
    void unknownPlaceholders() {
        assertThat(InstrumentTerms.unknownPlaceholders("{{salary}} {{applicantName}} {{bonus}} {{salary}} {tenor}"))
                .containsExactly("salary", "bonus");
        assertThat(InstrumentTerms.unknownPlaceholders("Pay {{ monthlyDeduction }} for {{tenor}} months")).isEmpty();
    }

    @Test
    @DisplayName("no term depends on something the loan only gets later, so a signed text is never left with a gap")
    void noLaterTerms() {
        assertThat(InstrumentTerms.placeholders()).doesNotContainKeys("startDate", "loanReference", "publicReference");
    }
}
