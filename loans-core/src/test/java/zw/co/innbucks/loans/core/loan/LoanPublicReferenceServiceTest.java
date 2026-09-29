package zw.co.innbucks.loans.core.loan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public reference's shape. The sequence behind it is created by the schema migrations and
 * drawn from PostgreSQL, which {@code LoansApiApplicationTests} exercises.
 */
class LoanPublicReferenceServiceTest {

    @Test
    @DisplayName("LN-<year>-<sequence>, the sequence padded to five digits and never truncated")
    void referenceFormat() {
        assertThat(LoanPublicReferenceService.format(2026, 42)).isEqualTo("LN-2026-00042");
        assertThat(LoanPublicReferenceService.format(2027, 1)).isEqualTo("LN-2027-00001");
        assertThat(LoanPublicReferenceService.format(2026, 1_234_567)).isEqualTo("LN-2026-1234567");
    }
}
